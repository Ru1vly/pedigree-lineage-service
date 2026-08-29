package com.edevlet.lineage.service;

import com.edevlet.lineage.domain.exception.DuplicateRequestException;
import com.edevlet.lineage.domain.exception.LineageNotFoundException;
import com.edevlet.lineage.domain.exception.UnauthorizedTaskAccessException;
import com.edevlet.lineage.domain.model.*;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.domain.repository.TransactionalOutboxRepository;
import com.edevlet.lineage.dto.LineageQueryAcceptedResponse;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.edevlet.lineage.dto.LineageQueryStatusResponse;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.PipelineRetryProperties;
import com.edevlet.lineage.infrastructure.util.UuidV7Generator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineageQueryService {

    private final LineageQueryRepository queryRepository;
    private final TransactionalOutboxRepository outboxRepository;
    private final LineageAuditLogRepository auditLogRepository;
    private final LineageTaskStateCache stateCache;
    private final ObjectMapper objectMapper;
    private final PipelineRetryProperties retryProperties;

    @Transactional
    public LineageQueryAcceptedResponse submitQuery(LineageQueryRequest request, NationalIdentityContext identity) {
        log.info("Submitting lineage query for userId={}, idempotencyKey={}", identity.userId(), request.getIdempotencyKey());

        // Idempotency check scoped to caller: idempotencyKey is unique per user (V3 migration)
        Optional<LineageQueryTask> existingTask =
                queryRepository.findByUserIdAndIdempotencyKey(identity.userId(), request.getIdempotencyKey());
        if (existingTask.isPresent()) {
            LineageQueryTask task = existingTask.get();
            log.info("Idempotent request match found. Returning existing transactionId={}", task.getTransactionId());
            return buildAcceptedResponse(task);
        }

        String transactionId = UuidV7Generator.generateString();
        String traceId = MDC.get("traceId");

        LineageQueryTask task = buildInitialTask(request, identity, transactionId, traceId);

        // The lookup above is a fast path, not a guarantee: two concurrent submits carrying the
        // same (userId, idempotencyKey) both miss it and both insert. The unique index from V3 is
        // the actual arbiter, and it fires on flush. saveAndFlush forces that flush here, inside a
        // frame that can name what happened - otherwise the violation surfaced at commit, after
        // this method had returned, and reached the client as an unhandled 500 from
        // GlobalExceptionHandler's catch-all. DuplicateRequestException is the answer this service
        // already defines and already maps to 409; it simply was never thrown.
        try {
            queryRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            log.info("Concurrent duplicate submit for userId={}, idempotencyKey={}; the competing request won the insert.",
                    identity.userId(), request.getIdempotencyKey());
            // This transaction is already poisoned by the failed insert, so the winner's
            // transactionId cannot be read back from here. Retrying the request is the client's
            // route to it: the idempotency lookup above will match by then and return 202.
            throw new DuplicateRequestException(request.getIdempotencyKey(), null);
        }

        LineageQueryMessage message = buildQueryMessage(request, identity, transactionId, traceId);

        // Transactional Outbox Event persistence: Debezium tails this insert off PostgreSQL WAL
        // and routes it to Kafka, guaranteeing atomic submission and message publication.
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("LineageQueryTask")
                .aggregateId(transactionId)
                .eventType("LineageQuerySubmitted")
                .payload(toJson(message))
                .build();
        outboxRepository.save(outboxEvent);

        LineageAuditLog auditLog = LineageAuditLog.builder()
                .transactionId(transactionId)
                .userId(identity.userId())
                .nationalId(request.getNationalId())
                .action("SUBMIT_LINEAGE_QUERY")
                .ipAddress(identity.ipAddress())
                .userAgent(identity.userAgent())
                .details("Submitted lineage query for generations depth: " + request.getGenerationsDepth())
                .build();
        auditLogRepository.save(auditLog);

        return buildAcceptedResponse(task);
    }

    /**
     * Status polling. Serves in-flight tasks from the Redis state cache and everything else from
     * Postgres.
     *
     * <p>The cache is only consulted for non-terminal tasks. That is the hot path - the SSE stream
     * polls this method on a fixed interval for the life of a query, and it is what the cache was
     * built for - while a COMPLETED task needs its ancestry result, which the cache deliberately
     * does not carry, and a FAILED one is read rarely. The ownership check runs identically on both
     * paths: a cache read that skipped it would be a way to read another citizen's task.
     *
     * <p>Deliberately NOT {@code @Transactional}. It was, and that made the cache-hit path -
     * the hot one, the one the SSE stream drives on a fixed interval - borrow a connection from
     * the pool and open a read transaction before discovering it did not need the database at all.
     * A cache that avoids the query but not the transaction has given back most of what it was
     * for. The database path below still runs transactionally: Spring Data's repository methods
     * are transactional in their own right, and this method reads column values from a single row
     * with nothing lazy behind it, so there is no cross-call consistency here to preserve.
     */
    public LineageQueryStatusResponse getQueryStatus(String transactionId, NationalIdentityContext identity) {
        Optional<LineageTaskStateCache.CachedTaskState> cachedState = stateCache.read(transactionId);
        if (cachedState.isPresent() && !cachedState.get().status().isTerminal()) {
            LineageTaskStateCache.CachedTaskState state = cachedState.get();
            validateCachedOwnership(state, identity, transactionId);
            return buildStatusResponseFromCache(state);
        }

        LineageQueryTask task = queryRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new LineageNotFoundException(transactionId));

        validateTaskOwnership(task, identity, transactionId);

        AncestryTree resultTree = parseResultPayload(task);
        int retryAfterSeconds = calculateRetryAfter(task.getStatus());
        String phaseDescription = task.getCurrentPhase() != null ? task.getCurrentPhase().getDescription() : "";

        return LineageQueryStatusResponse.builder()
                .transactionId(task.getTransactionId())
                .status(task.getStatus())
                .currentPhase(task.getCurrentPhase())
                .phaseDescription(phaseDescription)
                .progressPercentage(task.getProgressPercentage())
                .retryAfterSeconds(retryAfterSeconds)
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .completedAt(task.getCompletedAt())
                .result(resultTree)
                .resultDownloadUrl(task.getResultDownloadUrl())
                .errorCode(task.getErrorCode())
                .errorMessage(task.getErrorMessage())
                .build();
    }

    @Transactional
    public void cancelQuery(String transactionId, NationalIdentityContext identity) {
        LineageQueryTask task = queryRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new LineageNotFoundException(transactionId));

        validateTaskOwnership(task, identity, transactionId);

        if (task.getStatus() == TaskStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel a query task that has already COMPLETED.");
        }

        log.info("Cancelling query transactionId={} by userId={}", transactionId, identity.userId());
        task.setStatus(TaskStatus.FAILED);
        task.setErrorCode("TASK_CANCELLED_BY_USER");
        task.setErrorMessage("Lineage query task was manually cancelled by user.");
        task.setUpdatedAt(Instant.now());
        queryRepository.save(task);

        // The cached entry still says PROCESSING. Polling trusts the cache for non-terminal states,
        // so leaving it would report a cancelled task as still running until the TTL expired.
        // Evicting is enough: the next read misses and falls through to Postgres.
        stateCache.evict(transactionId);

        LineageAuditLog auditLog = LineageAuditLog.builder()
                .transactionId(transactionId)
                .userId(identity.userId())
                .nationalId(task.getNationalId())
                .action("CANCEL_LINEAGE_QUERY")
                .ipAddress(identity.ipAddress())
                .userAgent(identity.userAgent())
                .details("Cancelled lineage query transaction")
                .build();
        auditLogRepository.save(auditLog);
    }

    private LineageQueryStatusResponse buildStatusResponseFromCache(LineageTaskStateCache.CachedTaskState state) {
        String phaseDescription = state.currentPhase() != null ? state.currentPhase().getDescription() : "";
        return LineageQueryStatusResponse.builder()
                .transactionId(state.transactionId())
                .status(state.status())
                .currentPhase(state.currentPhase())
                .phaseDescription(phaseDescription)
                .progressPercentage(state.progressPercentage())
                .retryAfterSeconds(calculateRetryAfter(state.status()))
                .createdAt(state.createdAt())
                .updatedAt(state.updatedAt())
                .completedAt(state.completedAt())
                // Never populated on this path: the cache holds no result payload, and it is only
                // consulted for non-terminal tasks, which have no result to return.
                .result(null)
                .resultDownloadUrl(state.resultDownloadUrl())
                .errorCode(state.errorCode())
                .errorMessage(state.errorMessage())
                .build();
    }

    private void validateCachedOwnership(
            LineageTaskStateCache.CachedTaskState state, NationalIdentityContext identity, String transactionId) {
        if (!state.userId().equals(identity.userId()) && !identity.isAdmin()) {
            log.warn("Unauthorized access attempt on transactionId={} by userId={}", transactionId, identity.userId());
            throw new UnauthorizedTaskAccessException(identity.userId(), transactionId);
        }
    }

    private void validateTaskOwnership(LineageQueryTask task, NationalIdentityContext identity, String transactionId) {
        if (!task.getUserId().equals(identity.userId()) && !identity.isAdmin()) {
            log.warn("Unauthorized access attempt on transactionId={} by userId={}", transactionId, identity.userId());
            throw new UnauthorizedTaskAccessException(identity.userId(), transactionId);
        }
    }

    private AncestryTree parseResultPayload(LineageQueryTask task) {
        if (task.getStatus() != TaskStatus.COMPLETED || task.getResultPayload() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(task.getResultPayload(), AncestryTree.class);
        } catch (Exception parseException) {
            log.error("Failed to parse result payload for transactionId={}", task.getTransactionId(), parseException);
            return null;
        }
    }

    private LineageQueryTask buildInitialTask(
            LineageQueryRequest request,
            NationalIdentityContext identity,
            String transactionId,
            String traceId) {
        return LineageQueryTask.builder()
                .transactionId(transactionId)
                .idempotencyKey(request.getIdempotencyKey())
                .nationalId(request.getNationalId())
                .userId(identity.userId())
                .userRoles(String.join(",", identity.roles()))
                .status(TaskStatus.SUBMITTED)
                .currentPhase(ProcessingPhase.INITIATED)
                .progressPercentage(0)
                .generationsDepth(request.getGenerationsDepth())
                .includeCertificates(request.isIncludeCertificates())
                .documentFormat(request.getDocumentFormat())
                .requestPayload(toJson(request))
                .retryCount(0)
                // Configuration, not a literal: this is one multiplicand of the retry budget aimed
                // at the legacy census backend, and the other lives in resilience4j's config. Both
                // have to be visible in one place to be reasoned about - see PipelineRetryProperties.
                .maxRetries(retryProperties.getMaxRetries())
                .traceId(traceId)
                .build();
    }

    private LineageQueryMessage buildQueryMessage(
            LineageQueryRequest request,
            NationalIdentityContext identity,
            String transactionId,
            String traceId) {
        return LineageQueryMessage.builder()
                .transactionId(transactionId)
                .userId(identity.userId())
                .nationalId(request.getNationalId())
                .generationsDepth(request.getGenerationsDepth())
                .includeCertificates(request.isIncludeCertificates())
                .documentFormat(request.getDocumentFormat())
                .idempotencyKey(request.getIdempotencyKey())
                .traceId(traceId)
                // Carried across the outbox so the worker's audit rows can name the citizen's
                // origin rather than the worker that processed the task - see LineageTaskConsumer.
                .clientIpAddress(identity.ipAddress())
                .clientUserAgent(identity.userAgent())
                .submittedAt(Instant.now())
                .build();
    }

    private int calculateRetryAfter(TaskStatus status) {
        return switch (status) {
            case SUBMITTED, QUEUED -> 30;
            case PROCESSING -> 15;
            case COMPLETED, FAILED -> 0;
        };
    }

    private LineageQueryAcceptedResponse buildAcceptedResponse(LineageQueryTask task) {
        return LineageQueryAcceptedResponse.builder()
                .transactionId(task.getTransactionId())
                .status(task.getStatus())
                .currentPhase(task.getCurrentPhase())
                .progressPercentage(task.getProgressPercentage())
                .retryAfterSeconds(30)
                .createdAt(task.getCreatedAt())
                .statusUrl("/api/v1/lineage/queries/" + task.getTransactionId())
                .build();
    }

    private String toJson(Object object) {
        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException jsonException) {
            throw new IllegalStateException("Error serializing object to JSON", jsonException);
        }
    }
}
