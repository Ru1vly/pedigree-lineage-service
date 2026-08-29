package com.edevlet.lineage.infrastructure.pipeline;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.OutboxEvent;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.domain.repository.TransactionalOutboxRepository;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Records the outcome of a failed pipeline attempt in its own transaction.
 *
 * <p>Called by {@link LineagePipelineOrchestrator} from OUTSIDE any transaction, after
 * {@link LineagePipelinePhaseRunner}'s transaction has already rolled back. That ordering is the
 * whole point: the orchestrator rethrows on a terminal failure so Kafka routes the record to the
 * DLT, and anything written in the transaction that rethrow rolls back is lost - which is exactly
 * how the FAILED status carrying the real error, and its compliance audit-log row, used to
 * disappear, leaving only the DLT consumer's generic MAX_RETRIES_EXCEEDED_DLQ behind.
 *
 * <p>Because there is no surrounding transaction, plain {@code REQUIRED} starts a fresh one here
 * and commits independently. {@code REQUIRES_NEW} is deliberately NOT used: it would deadlock if
 * this were ever called from inside the phase transaction, which still holds the row lock on
 * {@code lineage_queries} while suspended.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineFailureHandler {

    private final LineageQueryRepository queryRepository;
    private final LineageAuditLogRepository auditLogRepository;
    private final TransactionalOutboxRepository outboxRepository;
    private final LineageTaskStateCache stateCache;
    private final ObjectMapper objectMapper;
    private final PipelineRetryProperties retryProperties;

    /**
     * Increments the task's retry count and either re-queues the message for another attempt
     * (returning true) or marks the task terminally FAILED with the real cause and writes the
     * compliance audit record (returning false).
     */
    @Transactional
    public boolean recordFailureAndMaybeRetry(String transactionId, LineageQueryMessage message, Exception failureException) {
        Optional<LineageQueryTask> taskOptional = queryRepository.findByTransactionId(transactionId);
        if (taskOptional.isEmpty()) {
            return false;
        }

        LineageQueryTask task = taskOptional.get();
        int nextRetryCount = task.getRetryCount() + 1;
        String errorMessage = failureException.getMessage() != null ? failureException.getMessage() : "Unknown execution failure";

        task.setRetryCount(nextRetryCount);
        task.setErrorCode("PIPELINE_EXECUTION_ERROR");
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(Instant.now());

        if (nextRetryCount <= task.getMaxRetries()) {
            scheduleRetry(task, message, transactionId, nextRetryCount, errorMessage);
            return true;
        }

        finalizeTerminalFailure(task, transactionId, errorMessage);
        return false;
    }

    private void scheduleRetry(
            LineageQueryTask task,
            LineageQueryMessage message,
            String transactionId,
            int nextRetryCount,
            String errorMessage) {
        Duration backoff = retryProperties.backoffForAttempt(nextRetryCount);
        log.warn("Pipeline attempt {} of {} failed for transactionId={}; re-queueing for retry in {}. Error: {}",
                nextRetryCount, task.getMaxRetries(), transactionId, backoff, errorMessage);
        task.setStatus(TaskStatus.SUBMITTED);
        task.setCurrentPhase(ProcessingPhase.INITIATED);
        task.setProgressPercentage(0);
        queryRepository.save(task);
        // The cache still holds the progress this attempt reached before failing. Polling trusts
        // it for non-terminal states, so a stale entry would keep reporting that progress while
        // the task sits re-queued at 0. See LineageTaskStateCache.
        stateCache.evict(transactionId);

        // Re-queue via a fresh outbox row rather than publishing to Kafka directly, so the
        // retry is committed atomically with the status rollback above and survives a crash
        // between the two - the same log-based CDC guarantee the initial submission gets.
        //
        // The outbox cannot itself delay anything: Debezium tails the insert off the WAL and the
        // record is on Kafka in milliseconds, which is exactly what made the retry budget arrive
        // as a burst against an already-failing backend. The attempt therefore carries the instant
        // it may start, and LineageTaskConsumer waits for it. See PipelineRetryProperties.
        outboxRepository.save(OutboxEvent.builder()
                .aggregateType("LineageQueryTask")
                .aggregateId(transactionId)
                .eventType("LineageQueryRetryRequested")
                .payload(toJson(withRetrySchedule(message, nextRetryCount, backoff)))
                .build());
    }

    /**
     * Copies the message with its retry schedule stamped on. A copy, not a mutation: the caller's
     * instance is the one the orchestrator is still holding for this attempt.
     */
    private LineageQueryMessage withRetrySchedule(LineageQueryMessage message, int retryAttempt, Duration backoff) {
        return LineageQueryMessage.builder()
                .transactionId(message.getTransactionId())
                .userId(message.getUserId())
                .nationalId(message.getNationalId())
                .generationsDepth(message.getGenerationsDepth())
                .includeCertificates(message.isIncludeCertificates())
                .documentFormat(message.getDocumentFormat())
                .idempotencyKey(message.getIdempotencyKey())
                .traceId(message.getTraceId())
                .submittedAt(message.getSubmittedAt())
                .clientIpAddress(message.getClientIpAddress())
                .clientUserAgent(message.getClientUserAgent())
                .retryAttempt(retryAttempt)
                .retryNotBefore(Instant.now().plus(backoff))
                .build();
    }

    private void finalizeTerminalFailure(LineageQueryTask task, String transactionId, String errorMessage) {
        log.error("Pipeline exhausted {} retries for transactionId={}; routing to DLQ.", task.getMaxRetries(), transactionId);
        task.setStatus(TaskStatus.FAILED);
        queryRepository.save(task);
        stateCache.evict(transactionId);
        auditPipelineFailure(transactionId,
                "Pipeline execution failed after " + task.getMaxRetries() + " retries: " + errorMessage);
    }

    private void auditPipelineFailure(String transactionId, String details) {
        UserSecurityContextHolder.getContext().ifPresentOrElse(identity -> {
            LineageAuditLog auditLog = LineageAuditLog.builder()
                    .transactionId(transactionId)
                    .userId(identity.userId())
                    .nationalId(identity.nationalId())
                    .action("LINEAGE_QUERY_FAILED")
                    .ipAddress(identity.ipAddress())
                    .userAgent(identity.userAgent())
                    .details(details)
                    .build();
            auditLogRepository.save(auditLog);
        }, () -> log.warn("No propagated identity context available to audit-log terminal failure for transactionId={}",
                transactionId));
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize object to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
