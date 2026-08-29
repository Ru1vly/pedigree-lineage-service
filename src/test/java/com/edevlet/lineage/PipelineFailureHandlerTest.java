package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.OutboxEvent;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.domain.repository.TransactionalOutboxRepository;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.PipelineFailureHandler;
import com.edevlet.lineage.infrastructure.pipeline.PipelineRetryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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

    @Mock
    private LineageTaskStateCache stateCache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final PipelineRetryProperties retryProperties = new PipelineRetryProperties();

    private PipelineFailureHandler failureHandler;

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        failureHandler = new PipelineFailureHandler(
                queryRepository, auditLogRepository, outboxRepository, stateCache, objectMapper,
                retryProperties);
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
    @DisplayName("A re-queued attempt carries a not-before instant so the retry budget is not fired back-to-back")
    void requeuedAttempt_CarriesBackoffSchedule() throws Exception {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId)
                .userId("user-123")
                .nationalId("12345678950")
                .build();

        LineageQueryTask task = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.PROCESSING)
                .retryCount(0)
                .maxRetries(3)
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(task));

        Instant before = Instant.now();
        failureHandler.recordFailureAndMaybeRetry(txId, message, new RuntimeException("census backend down"));

        ArgumentCaptor<OutboxEvent> outboxEvent = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxEvent.capture());

        LineageQueryMessage requeued =
                objectMapper.readValue(outboxEvent.getValue().getPayload(), LineageQueryMessage.class);

        // Without this, the outbox row is on Kafka within milliseconds of the failure and the next
        // attempt hits the already-failing census backend immediately - which is the whole reason
        // the retry budget could be spent as a burst.
        assertEquals(1, requeued.getRetryAttempt());
        assertNotNull(requeued.getRetryNotBefore());
        assertTrue(
                requeued.getRetryNotBefore().isAfter(before.plus(retryProperties.backoffForAttempt(1)).minusSeconds(1)),
                "the first retry should be scheduled roughly one backoff interval out, got "
                        + requeued.getRetryNotBefore());
    }

    @Test
    @DisplayName("Backoff grows per attempt and is capped, so a long-failing task does not schedule minutes out")
    void backoff_GrowsAndIsCapped() {
        PipelineRetryProperties properties = new PipelineRetryProperties();

        assertEquals(java.time.Duration.ZERO, properties.backoffForAttempt(0));
        assertEquals(properties.getInitialBackoff(), properties.backoffForAttempt(1));
        assertTrue(properties.backoffForAttempt(2).compareTo(properties.backoffForAttempt(1)) > 0);
        assertTrue(properties.backoffForAttempt(99).compareTo(properties.getMaxBackoff()) <= 0);
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
