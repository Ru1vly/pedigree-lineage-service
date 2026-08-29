package com.edevlet.lineage;

import com.edevlet.lineage.domain.exception.DuplicateRequestException;
import com.edevlet.lineage.domain.exception.UnauthorizedTaskAccessException;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.domain.repository.TransactionalOutboxRepository;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.edevlet.lineage.dto.LineageQueryStatusResponse;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.edevlet.lineage.service.LineageQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Submission and status-polling behaviour: the concurrent-duplicate answer, and the state cache
 * that status polling now actually reads.
 */
@ExtendWith(MockitoExtension.class)
class LineageQueryServiceTest {

    @Mock
    private LineageQueryRepository queryRepository;

    @Mock
    private TransactionalOutboxRepository outboxRepository;

    @Mock
    private LineageAuditLogRepository auditLogRepository;

    @Mock
    private LineageTaskStateCache stateCache;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private LineageQueryService service;

    private final NationalIdentityContext citizen = new NationalIdentityContext(
            "citizen-1", "12345678950", Set.of("ROLE_USER"), Set.of("lineage:read"), "203.0.113.7", "curl/8");

    @BeforeEach
    void setUp() {
        service = new LineageQueryService(
                queryRepository, outboxRepository, auditLogRepository, stateCache, objectMapper);
    }

    private LineageQueryRequest request() {
        return LineageQueryRequest.builder()
                .nationalId("12345678950")
                .generationsDepth(2)
                .idempotencyKey("idem-race-1")
                .build();
    }

    @Test
    @DisplayName("A concurrent duplicate submit answers 409, not an unhandled 500")
    void concurrentDuplicate_throwsDuplicateRequestException() {
        // Both requests miss the idempotency lookup; the unique index from V3 arbitrates on flush.
        given(queryRepository.findByUserIdAndIdempotencyKey("citizen-1", "idem-race-1"))
                .willReturn(Optional.empty());
        given(queryRepository.saveAndFlush(any(LineageQueryTask.class)))
                .willThrow(new DataIntegrityViolationException("uq_lineage_queries_user_idempotency"));

        assertThatThrownBy(() -> service.submitQuery(request(), citizen))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("idem-race-1");

        // The loser must not leave an outbox event or an audit row behind.
        verify(outboxRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("Polling an in-flight task is served from the state cache without touching Postgres")
    void inFlightStatus_isServedFromCache() {
        Instant now = Instant.now();
        given(stateCache.read("tx-1")).willReturn(Optional.of(new LineageTaskStateCache.CachedTaskState(
                "tx-1", "citizen-1", TaskStatus.PROCESSING, ProcessingPhase.IDENTITY_VERIFICATION,
                35, now.minusSeconds(5), now, null, null, null, null)));

        LineageQueryStatusResponse response = service.getQueryStatus("tx-1", citizen);

        assertThat(response.getStatus()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(response.getProgressPercentage()).isEqualTo(35);
        assertThat(response.getCurrentPhase()).isEqualTo(ProcessingPhase.IDENTITY_VERIFICATION);
        // The whole point of the cache: this is the hot path and it must not hit the database.
        verify(queryRepository, never()).findByTransactionId(anyString());
    }

    @Test
    @DisplayName("A cached entry belonging to another citizen is refused, exactly as the database path is")
    void cachedStateOfAnotherUser_isRefused() {
        given(stateCache.read("tx-2")).willReturn(Optional.of(new LineageTaskStateCache.CachedTaskState(
                "tx-2", "someone-else", TaskStatus.PROCESSING, ProcessingPhase.ANCESTRY_TRAVERSAL,
                10, Instant.now(), Instant.now(), null, null, null, null)));

        // A cache read that skipped the ownership check would be a way to read another citizen's task.
        assertThatThrownBy(() -> service.getQueryStatus("tx-2", citizen))
                .isInstanceOf(UnauthorizedTaskAccessException.class);
    }

    @Test
    @DisplayName("A COMPLETED task is read from Postgres so the ancestry result comes from the source of truth")
    void completedStatus_fallsThroughToPostgres() {
        given(stateCache.read("tx-3")).willReturn(Optional.of(new LineageTaskStateCache.CachedTaskState(
                "tx-3", "citizen-1", TaskStatus.COMPLETED, ProcessingPhase.FINISHED,
                100, Instant.now(), Instant.now(), Instant.now(), "/dl", null, null)));

        LineageQueryTask stored = LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId("tx-3")
                .userId("citizen-1")
                .status(TaskStatus.COMPLETED)
                .currentPhase(ProcessingPhase.FINISHED)
                .progressPercentage(100)
                .resultPayload("{\"rootPerson\":null,\"generations\":[],\"totalAncestorsFound\":0,"
                        + "\"verificationSealHash\":\"SEAL\",\"documentDownloadUrl\":\"/dl\"}")
                .resultDownloadUrl("/dl")
                .build();
        given(queryRepository.findByTransactionId("tx-3")).willReturn(Optional.of(stored));

        LineageQueryStatusResponse response = service.getQueryStatus("tx-3", citizen);

        // The cache deliberately carries no result payload, so a terminal read must not be served
        // from it - the citizen would get a COMPLETED status with no document.
        verify(queryRepository).findByTransactionId("tx-3");
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResultDownloadUrl()).isEqualTo("/dl");
    }

    @Test
    @DisplayName("A cache miss falls through to Postgres")
    void cacheMiss_fallsThroughToPostgres() {
        given(stateCache.read("tx-4")).willReturn(Optional.empty());
        given(queryRepository.findByTransactionId("tx-4")).willReturn(Optional.of(
                LineageQueryTask.builder()
                        .id(UUID.randomUUID())
                        .transactionId("tx-4")
                        .userId("citizen-1")
                        .status(TaskStatus.PROCESSING)
                        .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                        .progressPercentage(10)
                        .build()));

        assertThat(service.getQueryStatus("tx-4", citizen).getProgressPercentage()).isEqualTo(10);
        verify(queryRepository).findByTransactionId("tx-4");
    }
}
