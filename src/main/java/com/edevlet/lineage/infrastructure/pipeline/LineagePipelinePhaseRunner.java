package com.edevlet.lineage.infrastructure.pipeline;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.infrastructure.util.NationalIdMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The transactional body of the lineage pipeline: everything that touches the database for one task.
 *
 * <p>This is a separate bean from {@link LineagePipelineOrchestrator} so that each transaction ends
 * when its method returns. The orchestrator - which holds the distributed lock, decides about
 * retries and rethrows to reach the Kafka DLT - runs OUTSIDE any transaction, so by the time it
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
 *
 * <p>The run is split into {@link #beginProcessing} and {@link #completeWithAncestry} so the census
 * lookup between them happens with no transaction open. A single {@code runPhases} held a pooled
 * connection and the task's row lock across a network call to the legacy backend plus several
 * hundred milliseconds of simulated latency - on the worker tier whose whole premise is absorbing
 * bursts. Neither method below performs I/O beyond the database and the Redis status cache; the
 * orchestrator sequences them. Note that the two methods must be invoked through the proxy, i.e.
 * from the orchestrator - calling one from the other inside this class would bypass the transaction
 * advice entirely and silently re-merge the boundaries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineagePipelinePhaseRunner {

    private final LineageQueryRepository queryRepository;
    private final LineageAuditLogRepository auditLogRepository;
    private final LineageTaskStateCache stateCache;
    private final ObjectMapper objectMapper;

    /**
     * Claims the task for this attempt: phase 1 of 3. Returns false - having written nothing - when
     * the task is unknown or has already reached a terminal status, which is the check that actually
     * makes redelivery safe. The orchestrator skips the census lookup entirely on false.
     */
    @Transactional
    public boolean beginProcessing(LineageQueryMessage message) {
        String transactionId = message.getTransactionId();

        Optional<LineageQueryTask> taskOptional = queryRepository.findByTransactionId(transactionId);
        if (taskOptional.isEmpty()) {
            log.error("Task transactionId={} not found in database", transactionId);
            return false;
        }

        LineageQueryTask task = taskOptional.get();
        if (task.getStatus().isTerminal()) {
            log.info("Task transactionId={} is already in terminal status {}. Skipping execution.", transactionId, task.getStatus());
            return false;
        }

        log.info("Starting Pedigree Lineage Pipeline Execution for transactionId={}, nationalId={}",
                transactionId, maskNationalId(message.getNationalId()));
        log.info("Phase 1/3: Traversing Ancestry Tree for transactionId={}", transactionId);

        updateTaskPhase(task, TaskStatus.PROCESSING, ProcessingPhase.ANCESTRY_TRAVERSAL, 10);
        return true;
    }

    /**
     * Phase 2 of 3: civil-status and certificate verification. Its own transaction, so the 35%
     * checkpoint is a state the outside world can actually observe.
     *
     * <p>Phases 2 and 3 used to share one transaction with the completion write. Every intermediate
     * {@code progressPercentage} - 35 and 70 - was therefore overwritten by 100 before a single
     * commit occurred, so no poller could ever read either one. The SSE stream, the status endpoint
     * and the progress bar in the UI could only emit 0, 10 and 100; the two middle values existed
     * in the code and in the docs and nowhere else. Splitting the transaction is what makes them
     * real, because a phase boundary that never commits is not a phase boundary.
     */
    @Transactional
    public void verifyIdentityRecords(LineageQueryMessage message) {
        String transactionId = message.getTransactionId();
        LineageQueryTask task = requireTask(transactionId);

        log.info("Phase 2/3: Verifying Civil Status and Certificates for transactionId={}", transactionId);
        updateTaskPhase(task, TaskStatus.PROCESSING, ProcessingPhase.IDENTITY_VERIFICATION, 35);
    }

    /**
     * Phase 3 of 3, first half: document generation, committed on its own so 70% is observable for
     * the same reason 35% is. See {@link #verifyIdentityRecords}.
     */
    @Transactional
    public void generateDocuments(LineageQueryMessage message) {
        String transactionId = message.getTransactionId();
        LineageQueryTask task = requireTask(transactionId);

        log.info("Phase 3/3: Generating Certified Pedigree Tree and Verification Seal for transactionId={}", transactionId);
        updateTaskPhase(task, TaskStatus.PROCESSING, ProcessingPhase.DOCUMENT_GENERATION, 70);
    }

    /**
     * Stores the result and marks the task COMPLETED. The tree passed in is whatever the census
     * backend actually returned; there is no degraded or synthesised variant to guard against,
     * because an unavailable backend now propagates its failure instead of substituting invented
     * records - see LegacyCensusGraphClientImpl.
     */
    @Transactional
    public void completeWithAncestry(LineageQueryMessage message, AncestryTree ancestryTree) {
        String transactionId = message.getTransactionId();
        LineageQueryTask task = requireTask(transactionId);

        String downloadUrl = "/api/v1/lineage/documents/" + transactionId + "/download";

        // The census client returns the tree with a null documentDownloadUrl - it does not host
        // this service's documents and does not know the transactionId. Stamping the real URL here
        // is what stops every stored result payload from pointing at the same sample document.
        task.setResultPayload(toJson(ancestryTree.withDocumentDownloadUrl(downloadUrl)));
        task.setResultDownloadUrl(downloadUrl);
        task.setCompletedAt(Instant.now());
        updateTaskPhase(task, TaskStatus.COMPLETED, ProcessingPhase.FINISHED, 100);

        auditPipelineOutcome(transactionId, "LINEAGE_QUERY_COMPLETED", "Pipeline execution finished successfully.");
        log.info("Pipeline Execution Completed Successfully for transactionId={}", transactionId);
    }

    private LineageQueryTask requireTask(String transactionId) {
        return queryRepository.findByTransactionId(transactionId).orElseThrow(
                () -> new IllegalStateException("Task disappeared mid-pipeline: " + transactionId));
    }

    private void updateTaskPhase(LineageQueryTask task, TaskStatus status, ProcessingPhase phase, int progressPercentage) {
        task.setStatus(status);
        task.setCurrentPhase(phase);
        task.setProgressPercentage(progressPercentage);
        task.setUpdatedAt(Instant.now());
        queryRepository.save(task);

        // Queued for after this transaction commits, so the cache can never advertise progress a
        // rollback erased - and, unlike before, something actually reads it back. See
        // LineageTaskStateCache and LineageQueryService.getQueryStatus.
        stateCache.writeAfterCommit(task);
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

    private String maskNationalId(String nationalId) {
        return NationalIdMasker.mask(nationalId);
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
