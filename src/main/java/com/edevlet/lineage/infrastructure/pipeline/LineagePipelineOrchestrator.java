package com.edevlet.lineage.infrastructure.pipeline;

import com.edevlet.lineage.domain.exception.LockContentionException;
import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClient;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.tracing.MdcTaskDecorator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Drives one lineage task: takes the distributed lock, sequences the pipeline's transactional steps
 * around the external census lookup, and decides between a retry and a terminal failure.
 *
 * <p>This class is deliberately NOT {@code @Transactional}. The database work lives in
 * {@link LineagePipelinePhaseRunner}, so its transaction has committed or rolled back by the time
 * control returns here. That is what lets {@link PipelineFailureHandler} record a terminal failure
 * that survives: this method rethrows to route the record to the Kafka DLT, and when the failure
 * write shared a transaction with the pipeline body, the rethrow rolled the FAILED status and the
 * compliance audit row back with it - the task ended up FAILED only via the DLT consumer, under a
 * generic MAX_RETRIES_EXCEEDED_DLQ code, with the real cause and its audit record gone.
 *
 * <p>Being the non-transactional layer is also why the census lookup happens HERE, between two short
 * transactions, rather than inside one. It is a network call to a legacy backend; running it inside
 * {@code runPhases} held a pooled connection and the task's row lock open across the wire, on the
 * worker tier whose entire purpose is absorbing bursts. The phase runner now owns only database
 * work, which is what its own documentation always claimed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineagePipelineOrchestrator {

    private static final String LOCK_PREFIX = "lock:lineage:processing:";

    // Atomic compare-and-delete: only releases the lock if it still holds the token THIS worker
    // set. Without this, a worker that runs past the lock TTL can have a second worker acquire
    // the lock, then delete that second worker's lock out from under it on its own way out.
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end",
            Long.class);

    private final LineageQueryRepository queryRepository;
    private final LineagePipelinePhaseRunner phaseRunner;
    private final PipelineFailureHandler failureHandler;
    private final LegacyCensusGraphClient censusGraphClient;
    private final LineageTaskStateCache stateCache;
    private final StringRedisTemplate redisTemplate;

    public void executePipeline(LineageQueryMessage message) {
        String transactionId = message.getTransactionId();
        MdcTaskDecorator.setContext(message.getTraceId(), transactionId, message.getUserId());

        String lockKey = LOCK_PREFIX + transactionId;
        String lockToken = UUID.randomUUID().toString();
        boolean lockAcquired = false;

        try {
            // Distributed Lock Acquisition (Redis SETNX with TTL) with a fencing token
            // so only the worker that acquired the lock can release it.
            if (!acquireDistributedLock(lockKey, lockToken)) {
                // Throw rather than return: returning normally acks the record and drops it, so a
                // task whose lock holder later dies is never reprocessed. See LockContentionException.
                log.warn("Task transactionId={} is already held by another worker; releasing it for redelivery.",
                        transactionId);
                throw new LockContentionException(transactionId);
            }
            lockAcquired = true;

            // The task's real idempotency guard is the terminal-status check inside the phase
            // runner, not this lock. The lock is a throughput optimisation that keeps two workers
            // off the same task; correctness does not depend on holding it.
            if (phaseRunner.beginProcessing(message)) {
                // OUTSIDE any transaction on purpose - see the class comment.
                AncestryTree ancestryTree = censusGraphClient.traverseAncestryGraph(
                        message.getNationalId(), message.getGenerationsDepth());

                // Each phase is its own transaction and commits before the next begins. That is
                // what makes 35 and 70 states a poller can actually observe rather than values
                // overwritten by 100 before anything was ever committed.
                phaseRunner.verifyIdentityRecords(message);
                phaseRunner.generateDocuments(message);
                phaseRunner.completeWithAncestry(message, ancestryTree);
            }

        } catch (LockContentionException lockContention) {
            // Never a pipeline failure: the attempt did not fail, it never started. Recording a
            // cause or incrementing the retry count against the task here would be wrong.
            throw lockContention;
        } catch (Exception pipelineException) {
            log.error("Error executing lineage pipeline for transactionId={}", transactionId, pipelineException);
            // The phase transaction has already rolled back at this point, so this write commits
            // independently and survives any rethrow.
            boolean willRetry = failureHandler.recordFailureAndMaybeRetry(transactionId, message, pipelineException);
            if (!willRetry) {
                throw new RuntimeException("Pipeline execution failed for transactionId: " + transactionId, pipelineException);
            }
        } finally {
            if (lockAcquired) {
                releaseDistributedLock(lockKey, lockToken);
            }
            MdcTaskDecorator.clear();
        }
    }

    private boolean acquireDistributedLock(String lockKey, String lockToken) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofMinutes(10));
        return Boolean.TRUE.equals(acquired);
    }

    private void releaseDistributedLock(String lockKey, String lockToken) {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
    }

    /**
     * Terminal backstop invoked from the DLT consumer. The diagnosis it can offer is generic - it
     * only knows the message reached the Dead Letter Topic - so it must not clobber a specific
     * answer already recorded on the task. A generic backstop must never overwrite a specific
     * answer, and that applies to success just as much as to a specific failure cause.
     *
     * <p>The guard is {@link TaskStatus#isTerminal()}, not {@code FAILED && errorCode != null}.
     * The narrower check let a DLT arrival for an already-COMPLETED task rewrite it to
     * FAILED/MAX_RETRIES_EXCEEDED_DLQ while leaving {@code resultPayload} and
     * {@code resultDownloadUrl} populated - the download endpoint then refused, with 409, to serve
     * a finished result sitting in the very same row. That is reachable: a worker whose record is
     * redelivered under lock contention can exhaust the bounded backoff in KafkaConfig and land on
     * the DLT while the original holder goes on to complete the task normally. COMPLETED is an
     * answer; the DLT knows nothing that outranks it.
     */
    @Transactional
    public void finalizeFailure(String transactionId, String errorCode, String errorMessage) {
        log.warn("Finalizing failure status for transactionId={}, errorCode={}", transactionId, errorCode);
        queryRepository.findByTransactionId(transactionId).ifPresent(task -> {
            if (task.getStatus().isTerminal()) {
                log.info("Task transactionId={} is already in terminal status {} (errorCode={}); "
                                + "preserving it and recording the DLT arrival in the log alone.",
                        transactionId, task.getStatus(), task.getErrorCode());
                return;
            }
            task.setStatus(TaskStatus.FAILED);
            task.setErrorCode(errorCode);
            task.setErrorMessage(errorMessage);
            task.setUpdatedAt(Instant.now());
            queryRepository.save(task);
            // Drop any non-terminal cached state so polling stops reporting progress for a task
            // that has just been dead-lettered.
            stateCache.evict(transactionId);
        });
    }
}
