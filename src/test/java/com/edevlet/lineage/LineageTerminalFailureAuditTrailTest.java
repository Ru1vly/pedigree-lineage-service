package com.edevlet.lineage;

import com.edevlet.lineage.config.TestConfig;
import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClient;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelineOrchestrator;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Guards the compliance audit trail across the one path that used to erase it: a pipeline run that
 * exhausts its retries.
 *
 * <p>{@code executePipeline} is {@code @Transactional} and rethrows so Kafka routes the message to
 * the DLT. Writing the FAILED status and the audit-log row from inside that same transaction meant
 * the rethrow rolled both back - the task later became FAILED anyway via the DLT consumer, but
 * carrying a generic MAX_RETRIES_EXCEEDED_DLQ code, with the real reason and its audit record gone.
 *
 * <p>This test runs against a real Spring context, so {@code @Transactional} is applied by an actual
 * proxy and the rollback really happens. The existing {@code LineagePipelineOrchestratorTest} is
 * plain Mockito: it constructs the orchestrator with {@code new}, so no proxy, no transaction, and
 * no rollback - it asserted the in-memory entity was mutated and passed happily while the database
 * kept none of it. That is precisely why the defect stayed invisible, and why this test exists
 * alongside it rather than replacing it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class LineageTerminalFailureAuditTrailTest {

    @Autowired
    private LineagePipelineOrchestrator orchestrator;

    @Autowired
    private LineageQueryRepository queryRepository;

    @Autowired
    private LineageAuditLogRepository auditLogRepository;

    @MockBean
    private LegacyCensusGraphClient censusGraphClient;

    /**
     * Replaces the template outright rather than relying on TestConfig's @Primary mock: TestConfig
     * and RedisConfig both declare a bean named "stringRedisTemplate", so with
     * allow-bean-definition-overriding one simply replaces the other by name and @Primary never
     * comes into it. Whichever way that lands, the real template here would be built on a mocked
     * connection factory and fail the distributed-lock acquisition with "connection is required" -
     * failing the pipeline before it ever reaches the fault this test means to inject.
     */
    @MockBean
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private static final String FAILURE_REASON = "legacy census backend unavailable";

    @BeforeEach
    void stubRedisLock() {
        MockitoAnnotations.openMocks(this);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(Boolean.TRUE);
    }

    @BeforeEach
    void seedIdentityContext() {
        UserSecurityContextHolder.setContext(new NationalIdentityContext(
                "user-audit-trail",
                "12345678950",
                Set.of("ROLE_USER"),
                Set.of(),
                "10.1.2.3",
                "junit-test-agent"));
    }

    @AfterEach
    void clearIdentityContext() {
        UserSecurityContextHolder.clear();
    }

    @Test
    @DisplayName("Terminal pipeline failure survives the DLT rethrow with its real cause and audit record")
    void terminalFailureIsCommittedDespiteRollback() {
        String txId = "tx-audit-" + UUID.randomUUID();
        // retryCount already at maxRetries, so this attempt is terminal rather than a re-queue.
        queryRepository.saveAndFlush(LineageQueryTask.builder()
                .transactionId(txId)
                .idempotencyKey("idem-audit-" + UUID.randomUUID())
                .nationalId("12345678950")
                .userId("user-audit-trail")
                .status(TaskStatus.PROCESSING)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .generationsDepth(2)
                .includeCertificates(false)
                .documentFormat("PDF")
                .requestPayload("{}")
                .retryCount(3)
                .maxRetries(3)
                .build());

        given(censusGraphClient.traverseAncestryGraph(anyString(), anyInt()))
                .willThrow(new RuntimeException(FAILURE_REASON));

        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId(txId)
                .userId("user-audit-trail")
                .nationalId("12345678950")
                .generationsDepth(2)
                .includeCertificates(false)
                .documentFormat("PDF")
                .idempotencyKey("idem-audit-msg")
                .traceId("trace-audit")
                .submittedAt(Instant.now())
                .build();

        // Rethrown so Kafka's error handler routes the record to the DLT - and so the surrounding
        // transaction is marked rollback-only.
        assertThatThrownBy(() -> orchestrator.executePipeline(message))
                .isInstanceOf(RuntimeException.class);

        Optional<LineageQueryTask> reloaded = queryRepository.findByTransactionId(txId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(reloaded.get().getErrorCode()).isEqualTo("PIPELINE_EXECUTION_ERROR");
        assertThat(reloaded.get().getErrorMessage()).isEqualTo(FAILURE_REASON);
        assertThat(reloaded.get().getRetryCount()).isEqualTo(4);

        List<LineageAuditLog> auditLogs = auditLogRepository.findByTransactionIdOrderByTimestampDesc(txId);
        assertThat(auditLogs)
                .as("the compliance audit trail must retain the terminal failure after the rollback")
                .hasSize(1);
        assertThat(auditLogs.get(0).getAction()).isEqualTo("LINEAGE_QUERY_FAILED");
        assertThat(auditLogs.get(0).getUserId()).isEqualTo("user-audit-trail");
        assertThat(auditLogs.get(0).getDetails()).contains(FAILURE_REASON);
    }

    @Test
    @DisplayName("DLT consumer's generic backstop does not overwrite the recorded cause")
    void dltBackstopPreservesRecordedCause() {
        String txId = "tx-audit-backstop-" + UUID.randomUUID();
        queryRepository.saveAndFlush(LineageQueryTask.builder()
                .transactionId(txId)
                .idempotencyKey("idem-audit-" + UUID.randomUUID())
                .nationalId("12345678950")
                .userId("user-audit-trail")
                .status(TaskStatus.FAILED)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .generationsDepth(2)
                .includeCertificates(false)
                .documentFormat("PDF")
                .requestPayload("{}")
                .errorCode("PIPELINE_EXECUTION_ERROR")
                .errorMessage(FAILURE_REASON)
                .retryCount(4)
                .maxRetries(3)
                .build());

        orchestrator.finalizeFailure(txId, "MAX_RETRIES_EXCEEDED_DLQ",
                "Task moved to Dead Letter Topic after processing failures.");

        LineageQueryTask reloaded = queryRepository.findByTransactionId(txId).orElseThrow();
        assertThat(reloaded.getErrorCode()).isEqualTo("PIPELINE_EXECUTION_ERROR");
        assertThat(reloaded.getErrorMessage()).isEqualTo(FAILURE_REASON);
    }

    @Test
    @DisplayName("A task not already FAILED still gets the DLT backstop status")
    void dltBackstopStillAppliesWhenNoCauseRecorded() {
        String txId = "tx-audit-nocause-" + UUID.randomUUID();
        queryRepository.saveAndFlush(LineageQueryTask.builder()
                .transactionId(txId)
                .idempotencyKey("idem-audit-" + UUID.randomUUID())
                .nationalId("12345678950")
                .userId("user-audit-trail")
                .status(TaskStatus.PROCESSING)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .generationsDepth(2)
                .includeCertificates(false)
                .documentFormat("PDF")
                .requestPayload("{}")
                .retryCount(1)
                .maxRetries(3)
                .build());

        orchestrator.finalizeFailure(txId, "MAX_RETRIES_EXCEEDED_DLQ",
                "Task moved to Dead Letter Topic after processing failures.");

        LineageQueryTask reloaded = queryRepository.findByTransactionId(txId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(reloaded.getErrorCode()).isEqualTo("MAX_RETRIES_EXCEEDED_DLQ");
    }
}
