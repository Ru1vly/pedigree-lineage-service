package com.edevlet.lineage;

import com.edevlet.lineage.domain.exception.LockContentionException;
import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClient;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private LegacyCensusGraphClient censusGraphClient;

    @Mock
    private LineageTaskStateCache stateCache;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LineagePipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new LineagePipelineOrchestrator(
                queryRepository, phaseRunner, failureHandler, censusGraphClient, stateCache, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("executePipeline - Runs the pipeline body and releases the lock on success")
    void executePipeline_Success_RunsPhasesAndReleasesLock() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId).nationalId("12345678950").generationsDepth(2).build();
        AncestryTree tree = new AncestryTree(null, List.of(), 0, "SHA256-SEAL", "/doc");

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        given(phaseRunner.beginProcessing(message)).willReturn(true);
        given(censusGraphClient.traverseAncestryGraph("12345678950", 2)).willReturn(tree);

        assertDoesNotThrow(() -> orchestrator.executePipeline(message));

        verify(phaseRunner).beginProcessing(message);
        verify(phaseRunner).completeWithAncestry(message, tree);
        verify(failureHandler, never()).recordFailureAndMaybeRetry(anyString(), any(), any());
        verify(redisTemplate, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("executePipeline - A terminal task skips the census lookup entirely")
    void executePipeline_AlreadyTerminal_SkipsCensusLookup() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId).nationalId("12345678950").generationsDepth(2).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        given(phaseRunner.beginProcessing(message)).willReturn(false);

        assertDoesNotThrow(() -> orchestrator.executePipeline(message));

        // Redelivery of an already-finished task must not re-hit the legacy backend.
        verify(censusGraphClient, never()).traverseAncestryGraph(anyString(), anyInt());
        verify(phaseRunner, never()).completeWithAncestry(any(), any());
    }

    @Test
    @DisplayName("executePipeline - Lock contention throws so the record is redelivered, not acked")
    void executePipeline_AlreadyLocked_ThrowsForRedelivery() {
        String txId = UUID.randomUUID().toString();
        LineageQueryMessage message = LineageQueryMessage.builder().transactionId(txId).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.FALSE);

        // Returning normally here acked the record and dropped it: if the lock holder then died,
        // nothing reprocessed the task and it sat at PROCESSING forever. Throwing leaves the offset
        // uncommitted so the broker redelivers.
        assertThrows(LockContentionException.class, () -> orchestrator.executePipeline(message));

        verify(phaseRunner, never()).beginProcessing(any());
        // Contention is not a failed attempt: it must not touch the retry count or record a cause.
        verify(failureHandler, never()).recordFailureAndMaybeRetry(anyString(), any(), any());
        // The lock wasn't acquired by this worker, so it must never attempt to release
        // (let alone delete) a lock it doesn't hold.
        verify(redisTemplate, never()).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("executePipeline - Transient failure under maxRetries is acked, not dead-lettered")
    void executePipeline_TransientFailure_DoesNotRethrow() {
        String txId = UUID.randomUUID().toString();
        // nationalId/depth must be populated: anyString() does not match null, so a bare message
        // would leave the census stub unmatched and silently return null instead of throwing.
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId).nationalId("12345678950").generationsDepth(2).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        given(phaseRunner.beginProcessing(message)).willReturn(true);
        given(censusGraphClient.traverseAncestryGraph(anyString(), anyInt()))
                .willThrow(new RuntimeException("legacy census backend unavailable"));
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
        // nationalId/depth must be populated: anyString() does not match null, so a bare message
        // would leave the census stub unmatched and silently return null instead of throwing.
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId).nationalId("12345678950").generationsDepth(2).build();

        given(redisTemplate.opsForValue().setIfAbsent(eq("lock:lineage:processing:" + txId), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);
        given(phaseRunner.beginProcessing(message)).willReturn(true);
        given(censusGraphClient.traverseAncestryGraph(anyString(), anyInt()))
                .willThrow(new RuntimeException("legacy census backend unavailable"));
        given(failureHandler.recordFailureAndMaybeRetry(eq(txId), eq(message), any(Exception.class))).willReturn(false);

        assertThrows(RuntimeException.class, () -> orchestrator.executePipeline(message));

        // The failure must be recorded BEFORE the rethrow, and from outside any transaction the
        // rethrow could roll back - see PipelineFailureHandler.
        verify(failureHandler).recordFailureAndMaybeRetry(eq(txId), eq(message), any(Exception.class));
        verify(redisTemplate, times(1)).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("finalizeFailure - A DLT arrival for a COMPLETED task must not destroy the result")
    void finalizeFailure_CompletedTask_PreservesResult() {
        String txId = UUID.randomUUID().toString();
        String resultPayload = "{\"rootPerson\":{\"firstName\":\"AHMET\"}}";
        String downloadUrl = "/api/v1/lineage/documents/" + txId + "/download";

        LineageQueryTask completed = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.COMPLETED)
                .currentPhase(ProcessingPhase.FINISHED)
                .progressPercentage(100)
                .resultPayload(resultPayload)
                .resultDownloadUrl(downloadUrl)
                .completedAt(Instant.now())
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(completed));

        // This is reachable in production, not hypothetical: a record redelivered under lock
        // contention can exhaust KafkaConfig's bounded backoff and land on the DLT while the
        // original lock holder goes on to finish the task normally.
        orchestrator.finalizeFailure(txId, "MAX_RETRIES_EXCEEDED_DLQ", "Task moved to Dead Letter Topic.");

        // The old guard was `status == FAILED && errorCode != null`, which does not match a
        // COMPLETED task - so the backstop overwrote it with FAILED while leaving the result in
        // place, and the download endpoint then refused with 409 to serve a document sitting in
        // the same row.
        assertEquals(TaskStatus.COMPLETED, completed.getStatus());
        assertNull(completed.getErrorCode());
        assertEquals(resultPayload, completed.getResultPayload());
        assertEquals(downloadUrl, completed.getResultDownloadUrl());
        verify(queryRepository, never()).save(any());
    }

    @Test
    @DisplayName("finalizeFailure - A specific recorded failure cause outranks the generic DLT one")
    void finalizeFailure_AlreadyFailed_PreservesSpecificCause() {
        String txId = UUID.randomUUID().toString();
        LineageQueryTask failed = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.FAILED)
                .errorCode("PIPELINE_EXECUTION_ERROR")
                .errorMessage("legacy census backend unavailable")
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(failed));

        orchestrator.finalizeFailure(txId, "MAX_RETRIES_EXCEEDED_DLQ", "Task moved to Dead Letter Topic.");

        assertEquals("PIPELINE_EXECUTION_ERROR", failed.getErrorCode());
        assertEquals("legacy census backend unavailable", failed.getErrorMessage());
        verify(queryRepository, never()).save(any());
    }

    @Test
    @DisplayName("finalizeFailure - A task still in flight IS marked failed by the backstop")
    void finalizeFailure_NonTerminalTask_IsMarkedFailed() {
        String txId = UUID.randomUUID().toString();
        LineageQueryTask processing = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(txId)
                .status(TaskStatus.PROCESSING)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .progressPercentage(10)
                .build();

        given(queryRepository.findByTransactionId(txId)).willReturn(Optional.of(processing));

        orchestrator.finalizeFailure(txId, "MAX_RETRIES_EXCEEDED_DLQ", "Task moved to Dead Letter Topic.");

        // The backstop still does its job where there is no specific answer to protect.
        assertEquals(TaskStatus.FAILED, processing.getStatus());
        assertEquals("MAX_RETRIES_EXCEEDED_DLQ", processing.getErrorCode());
        verify(queryRepository).save(processing);
        // Any cached non-terminal progress must go, or polling keeps reporting a dead task as live.
        verify(stateCache).evict(txId);
    }
}
