package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.OutboxEvent;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.domain.repository.TransactionalOutboxRepository;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.PipelineFailureHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Retry bookkeeping and terminal-failure recording, split out of
 * {@code LineagePipelineOrchestratorTest} along with the code itself.
 */
@ExtendWith(MockitoExtension.class)
class PipelineFailureHandlerTest {

    @Mock
    private LineageQueryRepository queryRepository;

    @Mock
    private LineageAuditLogRepository auditLogRepository;

    @Mock
    private TransactionalOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PipelineFailureHandler failureHandler;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        failureHandler = new PipelineFailureHandler(
                queryRepository, auditLogRepository, outboxRepository, objectMapper);
    }

    @Test
    @DisplayName("Transient failure under maxRetries re-queues via the outbox instead of failing")
    void transientFailure_RequeuesViaOutbox() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId)
                .userId("user-123")
                .nationalId("12345678950")
                .generationsDepth(2)
                .build();

        LineageQueryTask task = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.PROCESSING)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .retryCount(0)
                .maxRetries(3)
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(task));

        boolean willRetry = failureHandler.recordFailureAndMaybeRetry(
                txId, message, new RuntimeException("legacy census backend unavailable"));

        assertTrue(willRetry);
        assertEquals(1, task.getRetryCount());
        assertEquals(TaskStatus.SUBMITTED, task.getStatus());
        verify(outboxRepository, times(1)).save(argThat(event ->
                event.getAggregateId().equals(txId)
                        && event.getEventType().equals("LineageQueryRetryRequested")
                        && event.getPayload().contains(txId)));
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Exhausted retries mark the task FAILED with the real cause and do not re-queue")
    void exhaustedRetries_MarksFailedWithRealCause() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        LineageQueryTask task = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.PROCESSING)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .retryCount(3)
                .maxRetries(3)
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(task));

        boolean willRetry = failureHandler.recordFailureAndMaybeRetry(
                txId, message, new RuntimeException("legacy census backend unavailable"));

        assertFalse(willRetry);
        assertEquals(4, task.getRetryCount());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        // The specific cause, not the DLT consumer's generic MAX_RETRIES_EXCEEDED_DLQ.
        assertEquals("PIPELINE_EXECUTION_ERROR", task.getErrorCode());
        assertEquals("legacy census backend unavailable", task.getErrorMessage());
        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("An exception with no message still records a non-null cause")
    void nullExceptionMessage_RecordsFallbackCause() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        LineageQueryTask task = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.PROCESSING)
                .retryCount(3)
                .maxRetries(3)
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(task));

        failureHandler.recordFailureAndMaybeRetry(txId, message, new RuntimeException());

        assertEquals("Unknown execution failure", task.getErrorMessage());
    }

    @Test
    @DisplayName("An unknown transactionId is treated as terminal rather than retried forever")
    void unknownTask_IsTerminal() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.empty());

        assertFalse(failureHandler.recordFailureAndMaybeRetry(txId, message, new RuntimeException("boom")));
        verify(outboxRepository, never()).save(any(OutboxEvent.class));
    }
}
