package com.edevlet.lineage.infrastructure.pipeline;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClient;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The transactional body of the lineage pipeline: everything that touches the database for one
 * task, committed or rolled back as a unit.
 *
 * <p>This is a separate bean from {@link LineagePipelineOrchestrator} so that the transaction ends
 * when this method returns. The orchestrator - which holds the distributed lock, decides about
 * retries and rethrows to reach the Kafka DLT - now runs OUTSIDE any transaction, so by the time it
 * records a failure, this transaction has already rolled back and released its row locks.
 *
 * <p>Keeping both in one {@code @Transactional} method is what lost the audit trail. The failure
 * write happened inside the transaction the subsequent rethrow rolled back, so the compliance
 * record and the real error code disappeared and only the DLT consumer's generic
 * MAX_RETRIES_EXCEEDED_DLQ survived. Recording it through {@code REQUIRES_NEW} from inside that
 * same method does not fix it either: the outer transaction has already flushed its UPDATE to
 * {@code lineage_queries} and holds the row lock, so the suspended-but-open outer transaction and
 * the new one deadlock on that row until the database times out ("Timeout trying to lock table").
 * Moving the boundary is the fix; the propagation setting is not.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineagePipelinePhaseRunner {

    private static final String STATE_PREFIX = "state:lineage:";

    private final LineageQueryRepository queryRepository;
    private final LineageAuditLogRepository auditLogRepository;
    private final LegacyCensusGraphClient censusGraphClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void runPhases(LineageQueryMessage message) {
        String transactionId = message.getTransactionId();

        Optional<LineageQueryTask> taskOptional = queryRepository.findByTransactionId(transactionId);
        if (taskOptional.isEmpty()) {
            log.error("Task transactionId={} not found in database", transactionId);
            return;
        }

        LineageQueryTask task = taskOptional.get();
        if (task.getStatus().isTerminal()) {
            log.info("Task transactionId={} is already in terminal status {}. Skipping execution.", transactionId, task.getStatus());
            return;
        }

        log.info("Starting Pedigree Lineage Pipeline Execution for transactionId={}, nationalId={}",
                transactionId, maskNationalId(message.getNationalId()));

        updateTaskPhase(task, TaskStatus.PROCESSING, ProcessingPhase.ANCESTRY_TRAVERSAL, 10);

        AncestryTree ancestryTree = executeAncestryTraversalPhase(task, message);
        executeCivilVerificationPhase(task);
        executeDocumentGenerationPhase(task, ancestryTree);

        auditPipelineOutcome(transactionId, "LINEAGE_QUERY_COMPLETED", "Pipeline execution finished successfully.");
        log.info("Pipeline Execution Completed Successfully for transactionId={}", transactionId);
    }

    private AncestryTree executeAncestryTraversalPhase(LineageQueryTask task, LineageQueryMessage message) {
        log.info("Phase 1/3: Traversing Ancestry Tree for transactionId={}", task.getTransactionId());
        AncestryTree ancestryTree = censusGraphClient.traverseAncestryGraph(
                message.getNationalId(), message.getGenerationsDepth());
        updateTaskPhase(task, TaskStatus.PROCESSING, ProcessingPhase.IDENTITY_VERIFICATION, 35);
        return ancestryTree;
    }

    private void executeCivilVerificationPhase(LineageQueryTask task) {
        log.info("Phase 2/3: Verifying Civil Status and Certificates for transactionId={}", task.getTransactionId());
        simulateProcessingDelay(200);
        updateTaskPhase(task, TaskStatus.PROCESSING, ProcessingPhase.DOCUMENT_GENERATION, 70);
    }

    private void executeDocumentGenerationPhase(LineageQueryTask task, AncestryTree ancestryTree) {
        log.info("Phase 3/3: Generating Certified Pedigree Tree and Verification Seal for transactionId={}", task.getTransactionId());
        simulateProcessingDelay(200);

        String documentDownloadUrl = "/api/v1/lineage/documents/" + task.getTransactionId() + "/download";
        task.setResultPayload(toJson(ancestryTree));
        task.setResultDownloadUrl(documentDownloadUrl);
        task.setCompletedAt(Instant.now());
        updateTaskPhase(task, TaskStatus.COMPLETED, ProcessingPhase.FINISHED, 100);
    }

    private void updateTaskPhase(LineageQueryTask task, TaskStatus status, ProcessingPhase phase, int progressPercentage) {
        task.setStatus(status);
        task.setCurrentPhase(phase);
        task.setProgressPercentage(progressPercentage);
        task.setUpdatedAt(Instant.now());
        queryRepository.save(task);

        // Update Redis Cache for ultra-fast polling
        try {
            redisTemplate.opsForValue().set(STATE_PREFIX + task.getTransactionId(),
                    toJson(task), Duration.ofHours(1));
        } catch (Exception e) {
            log.warn("Failed to update Redis cache for transactionId={}: {}", task.getTransactionId(), e.getMessage());
        }
    }

    /**
     * Uses the NationalIdentityContext propagated onto this worker thread by LineageTaskConsumer,
     * rather than duplicating identity fields off the message - the propagation existed but
     * nothing downstream ever read it back until this audit trail was added.
     */
    private void auditPipelineOutcome(String transactionId, String action, String details) {
        UserSecurityContextHolder.getContext().ifPresentOrElse(identity -> {
            LineageAuditLog auditLog = LineageAuditLog.builder()
                    .transactionId(transactionId)
                    .userId(identity.userId())
                    .nationalId(identity.nationalId())
                    .action(action)
                    .ipAddress(identity.ipAddress())
                    .userAgent(identity.userAgent())
                    .details(details)
                    .build();
            auditLogRepository.save(auditLog);
        }, () -> log.warn("No propagated identity context available to audit-log transactionId={}", transactionId));
    }

    private void simulateProcessingDelay(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String maskNationalId(String nationalId) {
        if (nationalId == null || nationalId.length() < 11) {
            return "123*****901";
        }
        return nationalId.substring(0, 3) + "*****" + nationalId.substring(8);
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
