package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.AncestryTree.AncestorPerson;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelinePhaseRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * The transactional body of the pipeline, exercised directly. Split out of
 * {@code LineagePipelineOrchestratorTest} when the transaction boundary moved off the orchestrator
 * and onto this bean.
 */
@ExtendWith(MockitoExtension.class)
class LineagePipelinePhaseRunnerTest {

    @Mock
    private LineageQueryRepository queryRepository;

    @Mock
    private LineageAuditLogRepository auditLogRepository;

    @Mock
    private LineageTaskStateCache stateCache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LineagePipelinePhaseRunner phaseRunner;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        phaseRunner = new LineagePipelinePhaseRunner(
                queryRepository, auditLogRepository, stateCache, objectMapper);
    }

    @Test
    @DisplayName("beginProcessing then completeWithAncestry - Completes execution and updates state")
    void runPhases_Success() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId)
                .userId("user-123")
                .nationalId("12345678950")
                .generationsDepth(2)
                .includeCertificates(false)
                .documentFormat("PDF")
                .idempotencyKey("idem-001")
                .submittedAt(Instant.now())
                .build();

        LineageQueryTask task = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .idempotencyKey("idem-001")
                .nationalId("12345678950")
                .userId("user-123")
                .status(TaskStatus.SUBMITTED)
                .currentPhase(ProcessingPhase.INITIATED)
                .progressPercentage(0)
                .generationsDepth(2)
                .requestPayload("{}")
                .build();

        AncestorPerson rootPerson = new AncestorPerson("123*****950", "AHMET", "YILMAZ", "MEHMET", "FATMA", LocalDate.of(1985, 4, 12), "ANKARA", "SAĞ", "KENDİSİ");
        AncestryTree mockTree = new AncestryTree(rootPerson, List.of(), 1, "SHA256-SEAL", "/doc");

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(task));

        assertTrue(phaseRunner.beginProcessing(message));
        assertEquals(TaskStatus.PROCESSING, task.getStatus());
        assertEquals(ProcessingPhase.ANCESTRY_TRAVERSAL, task.getCurrentPhase());
        assertEquals(10, task.getProgressPercentage());

        // The census lookup happens in the orchestrator, between these transactions, so the tree
        // arrives as an argument. Nothing in this bean performs I/O beyond the database.
        //
        // Each phase below is a separate transactional method for a reason: they used to run inside
        // one transaction, so 35 and 70 were overwritten by 100 before anything committed and no
        // poller could ever see them. Asserting the intermediate values between calls is what pins
        // that down - if the phases are ever merged back, these assertions fail.
        phaseRunner.verifyIdentityRecords(message);
        assertEquals(ProcessingPhase.IDENTITY_VERIFICATION, task.getCurrentPhase());
        assertEquals(35, task.getProgressPercentage());
        assertEquals(TaskStatus.PROCESSING, task.getStatus());

        phaseRunner.generateDocuments(message);
        assertEquals(ProcessingPhase.DOCUMENT_GENERATION, task.getCurrentPhase());
        assertEquals(70, task.getProgressPercentage());
        assertEquals(TaskStatus.PROCESSING, task.getStatus());

        phaseRunner.completeWithAncestry(message, mockTree);

        verify(queryRepository, atLeast(4)).save(task);
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(ProcessingPhase.FINISHED, task.getCurrentPhase());
        assertEquals(100, task.getProgressPercentage());
        assertNotNull(task.getResultPayload());
        assertNotNull(task.getResultDownloadUrl());

        // The stored result must carry this task's own download URL, not the census client's
        // hardcoded "/api/v1/lineage/documents/sample/download".
        assertEquals("/api/v1/lineage/documents/" + txId + "/download", task.getResultDownloadUrl());
        assertTrue(task.getResultPayload().contains("/api/v1/lineage/documents/" + txId + "/download"),
                "the serialized ancestry tree should carry this task's download URL");

        // Every committed phase transition is published to the state cache that status polling
        // reads - the write end that used to exist with no reader.
        verify(stateCache, atLeast(4)).writeAfterCommit(task);
    }

    @Test
    @DisplayName("beginProcessing - A task already in a terminal status is not re-executed")
    void runPhases_AlreadyTerminal_Skips() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId)
                .nationalId("12345678950")
                .generationsDepth(2)
                .build();

        LineageQueryTask task = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.COMPLETED)
                .currentPhase(ProcessingPhase.FINISHED)
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(task));

        // False tells the orchestrator to skip the census lookup entirely. This check, not the
        // Redis lock, is what actually makes redelivery safe.
        assertFalse(phaseRunner.beginProcessing(message));
        verify(queryRepository, never()).save(any(LineageQueryTask.class));
    }

    @Test
    @DisplayName("beginProcessing - An unknown transactionId is logged and skipped, not NPE'd")
    void runPhases_TaskNotFound_Skips() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.empty());

        assertFalse(phaseRunner.beginProcessing(message));
        verify(queryRepository, never()).save(any(LineageQueryTask.class));
    }
}
