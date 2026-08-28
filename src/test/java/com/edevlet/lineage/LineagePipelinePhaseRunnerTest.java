package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.AncestryTree.AncestorPerson;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClient;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelinePhaseRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
    private LegacyCensusGraphClient censusGraphClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LineagePipelinePhaseRunner phaseRunner;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        phaseRunner = new LineagePipelinePhaseRunner(
                queryRepository, auditLogRepository, censusGraphClient, redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("runPhases - Completes 3-phase execution successfully and updates state")
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
        given(censusGraphClient.traverseAncestryGraph(eq("12345678950"), eq(2))).willReturn(mockTree);

        phaseRunner.runPhases(message);

        verify(queryRepository, atLeast(4)).save(task);
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals(ProcessingPhase.FINISHED, task.getCurrentPhase());
        assertEquals(100, task.getProgressPercentage());
        assertNotNull(task.getResultPayload());
        assertNotNull(task.getResultDownloadUrl());
    }

    @Test
    @DisplayName("runPhases - A task already in a terminal status is not re-executed")
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

        phaseRunner.runPhases(message);

        verify(censusGraphClient, never()).traverseAncestryGraph(anyString(), anyInt());
        verify(queryRepository, never()).save(any(LineageQueryTask.class));
    }

    @Test
    @DisplayName("runPhases - An unknown transactionId is logged and skipped, not NPE'd")
    void runPhases_TaskNotFound_Skips() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.empty());

        assertDoesNotThrow(() -> phaseRunner.runPhases(message));
        verify(censusGraphClient, never()).traverseAncestryGraph(anyString(), anyInt());
    }
}
