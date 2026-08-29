package com.edevlet.lineage;

import com.edevlet.lineage.config.TestConfig;
import com.edevlet.lineage.domain.exception.LineageResultNotReadyException;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClient;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelineOrchestrator;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.web.LineageDocumentController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * The census backend is unreachable. Nothing the citizen can reach may present a result.
 *
 * <p>This is the regression guard for the defect that mattered most: the client answered an
 * unreachable backend with an invented tree - "CITIZEN RECORD" / "UNKNOWN" / a literal
 * "SHA256-DEGRADED-SEAL" - and because no code downstream could distinguish that from a real answer,
 * the pipeline marked the task COMPLETED at 100% and the document endpoint served those fabricated
 * ancestors under a footer citing Law No. 5070 on secure electronic signatures. During an outage the
 * service issued citizens official-looking pedigree documents for ancestry that does not exist, and
 * recorded every one as a success.
 *
 * <p>Runs against a real Spring context so the transaction boundaries are real. A pure-Mockito test
 * could not see this: the property under test is what survives in the database and what the document
 * endpoint will actually serve.
 *
 * <p><b>What this test can and cannot see.</b> It mocks the census client, so it proves the
 * pipeline-and-document contract: <em>given</em> a failed lookup, nothing completes and nothing is
 * downloadable. It does not by itself prove the fallback is gone - a mocked client has no
 * resilience4j proxy to apply one. That half is guarded structurally by
 * {@code LegacyCensusGraphClientTest.testNoFallbackMethodExists}, which is the assertion that
 * actually fails if someone reintroduces a fallback method for the circuit breaker to resolve by
 * name. The two together are the regression guard; neither is sufficient alone.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class CensusBackendOutageTest {

    private static final String OUTAGE_CAUSE = "legacy census database connection timeout";

    @Autowired
    private LineagePipelineOrchestrator orchestrator;

    @Autowired
    private LineageQueryRepository queryRepository;

    @Autowired
    private LineageDocumentController documentController;

    @MockBean
    private LegacyCensusGraphClient censusGraphClient;

    /** See LineageTerminalFailureAuditTrailTest for why the template is replaced outright. */
    @MockBean
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private NationalIdentityContext identity;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).willReturn(Boolean.TRUE);

        identity = new NationalIdentityContext(
                "user-outage", "12345678950", Set.of("ROLE_USER"), Set.of(), "10.0.0.9", "junit-test-agent");
        UserSecurityContextHolder.setContext(identity);

        given(censusGraphClient.traverseAncestryGraph(anyString(), anyInt()))
                .willThrow(new RuntimeException(OUTAGE_CAUSE));
    }

    @AfterEach
    void tearDown() {
        UserSecurityContextHolder.clear();
    }

    @Test
    @DisplayName("A census outage fails the task instead of completing it with invented ancestry")
    void outageFailsTaskRatherThanFabricatingAResult() {
        String txId = seedTaskOnItsFinalAttempt();

        LineageQueryMessage message = messageFor(txId);
        assertThatThrownBy(() -> orchestrator.executePipeline(message))
                .isInstanceOf(RuntimeException.class);

        LineageQueryTask reloaded = queryRepository.findByTransactionId(txId).orElseThrow();

        assertThat(reloaded.getStatus())
                .as("an unreachable backend must never yield a COMPLETED task")
                .isEqualTo(TaskStatus.FAILED);
        assertThat(reloaded.getProgressPercentage())
                .as("progress must not reach 100 on a failed lookup")
                .isNotEqualTo(100);
        assertThat(reloaded.getErrorMessage())
                .as("the real cause has to survive, not be replaced by a degraded placeholder")
                .isEqualTo(OUTAGE_CAUSE);

        assertThat(reloaded.getResultPayload())
                .as("no ancestry may be persisted when none was retrieved")
                .isNull();
        assertThat(reloaded.getResultDownloadUrl())
                .as("no document may be offered for a lookup that never succeeded")
                .isNull();
    }

    @Test
    @DisplayName("No certificate is downloadable for a task the census outage failed")
    void noDownloadableCertificateAfterOutage() {
        String txId = seedTaskOnItsFinalAttempt();

        assertThatThrownBy(() -> orchestrator.executePipeline(messageFor(txId)))
                .isInstanceOf(RuntimeException.class);

        // GlobalExceptionHandler maps this to 409 CONFLICT - a 4xx, not a 200 carrying a
        // government document full of ancestors nobody ever retrieved.
        assertThatThrownBy(() -> documentController.downloadPedigreeDocument(txId))
                .as("the document endpoint must refuse a task that never produced a result")
                .isInstanceOf(LineageResultNotReadyException.class);
    }

    private String seedTaskOnItsFinalAttempt() {
        String txId = "tx-outage-" + UUID.randomUUID();
        // retryCount already at maxRetries, so this attempt is terminal rather than a re-queue.
        queryRepository.saveAndFlush(LineageQueryTask.builder()
                .transactionId(txId)
                .idempotencyKey("idem-outage-" + UUID.randomUUID())
                .nationalId("12345678950")
                .userId("user-outage")
                .status(TaskStatus.SUBMITTED)
                .currentPhase(ProcessingPhase.INITIATED)
                .progressPercentage(0)
                .generationsDepth(2)
                .includeCertificates(true)
                .documentFormat("PDF")
                .requestPayload("{}")
                .retryCount(3)
                .maxRetries(3)
                .build());
        return txId;
    }

    private LineageQueryMessage messageFor(String txId) {
        return LineageQueryMessage.builder()
                .transactionId(txId)
                .userId("user-outage")
                .nationalId("12345678950")
                .generationsDepth(2)
                .includeCertificates(true)
                .documentFormat("PDF")
                .idempotencyKey("idem-outage-msg")
                .traceId("trace-outage")
                .submittedAt(Instant.now())
                .build();
    }
}
