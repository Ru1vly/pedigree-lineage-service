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
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.util.UuidV7Generator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
    private final ObjectMapper objectMapper;

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
        queryRepository.save(task);

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

    @Transactional(readOnly = true)
    public LineageQueryStatusResponse getQueryStatus(String transactionId, NationalIdentityContext identity) {
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
                .maxRetries(3)
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
