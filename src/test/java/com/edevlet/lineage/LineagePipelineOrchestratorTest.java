package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelineOrchestrator;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelinePhaseRunner;
import com.edevlet.lineage.infrastructure.pipeline.PipelineFailureHandler;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Orchestration-level behaviour only: the distributed lock, delegation to the transactional phase
 * body, and the retry-versus-dead-letter decision. The pipeline's own database work is covered by
 * {@link LineagePipelinePhaseRunnerTest} and the failure bookkeeping by
 * {@link PipelineFailureHandlerTest}.
 *
 * <p>Note what this test cannot see: it builds the orchestrator with {@code new}, so there is no
 * Spring proxy and no transaction anywhere. That is exactly why the lost-audit-trail defect hid
 * from the suite for so long - assertions on a mutated in-memory entity pass whether or not the
 * write survives a rollback. {@code LineageTerminalFailureAuditTrailTest} covers that against a
 * real transaction manager and is the test that would catch a regression here.
 */
@ExtendWith(MockitoExtension.class)
class LineagePipelineOrchestratorTest {

    @Mock
    private LineageQueryRepository queryRepository;

    @Mock
    private LineagePipelinePhaseRunner phaseRunner;

    @Mock
    private PipelineFailureHandler failureHandler;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LineagePipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new LineagePipelineOrchestrator(queryRepository, phaseRunner, failureHandler, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("executePipeline - Runs the pipeline body and releases the lock on success")
    void executePipeline_Success_RunsPhasesAndReleasesLock() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);

        assertDoesNotThrow(() -> orchestrator.executePipeline(message));

        verify(phaseRunner).runPhases(message);
        verify(failureHandler, never()).recordFailureAndMaybeRetry(anyString(), any(), any());
        verify(redisTemplate, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("executePipeline - Concurrent processing lock prevents duplicate pipeline execution")
    void executePipeline_AlreadyLocked_Skips() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.FALSE);

        orchestrator.executePipeline(message);

        verify(phaseRunner, never()).runPhases(any());
        // The lock wasn't acquired by this worker, so it must never attempt to release
        // (let alone delete) a lock it doesn't hold.
        verify(redisTemplate, never()).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("executePipeline - Transient failure under maxRetries is acked, not dead-lettered")
    void executePipeline_TransientFailure_DoesNotRethrow() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        doThrow(new RuntimeException("legacy census backend unavailable")).when(phaseRunner).runPhases(message);
        given(failureHandler.recordFailureAndMaybeRetry(eq(txId), eq(message), any(Exception.class))).willReturn(true);

        // Retries are re-queued as a fresh outbox row (routed back through Debezium/Kafka like any
        // other event), not dead-lettered - so the current delivery must ack normally, and the lock
        // must still be released for the retry to proceed.
        assertDoesNotThrow(() -> orchestrator.executePipeline(message));

        verify(redisTemplate, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("executePipeline - Failure after maxRetries is exhausted rethrows to reach the Kafka DLT")
    void executePipeline_RetriesExhausted_Rethrows() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        doThrow(new RuntimeException("legacy census backend unavailable")).when(phaseRunner).runPhases(message);
        given(failureHandler.recordFailureAndMaybeRetry(eq(txId), eq(message), any(Exception.class))).willReturn(false);

        assertThrows(RuntimeException.class, () -> orchestrator.executePipeline(message));

        // The failure must be recorded BEFORE the rethrow, and from outside any transaction the
        // rethrow could roll back - see PipelineFailureHandler.
        verify(failureHandler).recordFailureAndMaybeRetry(eq(txId), eq(message), any(Exception.class));
        verify(redisTemplate, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }
}
