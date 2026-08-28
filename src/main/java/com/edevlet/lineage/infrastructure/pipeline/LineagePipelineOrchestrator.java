package com.edevlet.lineage.infrastructure.pipeline;

import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
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
 * Drives one lineage task: takes the distributed lock, runs the pipeline's transactional body,
 * and decides between a retry and a terminal failure.
 *
 * <p>This class is deliberately NOT {@code @Transactional}. The database work lives in
 * {@link LineagePipelinePhaseRunner}, so its transaction has committed or rolled back by the time
 * control returns here. That is what lets {@link PipelineFailureHandler} record a terminal failure
 * that survives: this method rethrows to route the record to the Kafka DLT, and when the failure
 * write shared a transaction with the pipeline body, the rethrow rolled the FAILED status and the
 * compliance audit row back with it - the task ended up FAILED only via the DLT consumer, under a
 * generic MAX_RETRIES_EXCEEDED_DLQ code, with the real cause and its audit record gone.
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
                log.warn("Task transactionId={} is already being processed by another worker. Skipping.", transactionId);
                return;
            }
            lockAcquired = true;

            phaseRunner.runPhases(message);

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
     * cause already recorded by PipelineFailureHandler on the way out of the pipeline. It therefore
     * fills in the error code and message only when the task is not already FAILED, and otherwise
     * records the DLT arrival in the log alone.
     */
    @Transactional
    public void finalizeFailure(String transactionId, String errorCode, String errorMessage) {
        log.warn("Finalizing failure status for transactionId={}, errorCode={}", transactionId, errorCode);
        queryRepository.findByTransactionId(transactionId).ifPresent(task -> {
            if (task.getStatus() == TaskStatus.FAILED && task.getErrorCode() != null) {
                log.info("Task transactionId={} already terminally FAILED with errorCode={}; preserving the recorded cause.",
                        transactionId, task.getErrorCode());
                return;
            }
            task.setStatus(TaskStatus.FAILED);
            task.setErrorCode(errorCode);
            task.setErrorMessage(errorMessage);
            task.setUpdatedAt(Instant.now());
            queryRepository.save(task);
        });
    }
}
