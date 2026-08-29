package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.infrastructure.cache.LineageTaskStateCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LineageTaskStateCacheTest {

    private static final String KEY = "state:lineage:tx-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private LineageTaskStateCache cache;

    @BeforeEach
    void setUp() {
        cache = new LineageTaskStateCache(redisTemplate, objectMapper);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private LineageQueryTask task(TaskStatus status, ProcessingPhase phase, int progress) {
        return LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId("tx-1")
                .userId("citizen-1")
                .status(status)
                .currentPhase(phase)
                .progressPercentage(progress)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("An in-flight transition is cached with a TTL")
    void inFlightTransition_isCached() {
        cache.writeAfterCommit(task(TaskStatus.PROCESSING, ProcessingPhase.IDENTITY_VERIFICATION, 35));

        verify(valueOperations).set(eq(KEY), any(String.class), eq(Duration.ofHours(1)));
        verify(redisTemplate, never()).delete(KEY);
    }

    @Test
    @DisplayName("Reaching a terminal status evicts rather than caching")
    void terminalTransition_evicts() {
        cache.writeAfterCommit(task(TaskStatus.COMPLETED, ProcessingPhase.FINISHED, 100));

        // Readers go to Postgres for terminal tasks anyway. Writing here risks the opposite
        // failure: a write that fails leaves a stale 70% entry pinning a finished task for the
        // whole TTL. A delete that fails just becomes a cache miss.
        verify(redisTemplate).delete(KEY);
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    @DisplayName("A cached snapshot round-trips through JSON")
    void snapshot_roundTrips() throws Exception {
        LineageQueryTask source = task(TaskStatus.PROCESSING, ProcessingPhase.DOCUMENT_GENERATION, 70);
        cache.writeAfterCommit(source);

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), payload.capture(), any(Duration.class));

        given(valueOperations.get(KEY)).willReturn(payload.getValue());
        Optional<LineageTaskStateCache.CachedTaskState> read = cache.read("tx-1");

        assertThat(read).isPresent();
        assertThat(read.get().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(read.get().progressPercentage()).isEqualTo(70);
        assertThat(read.get().currentPhase()).isEqualTo(ProcessingPhase.DOCUMENT_GENERATION);
        // Carried so readers can enforce the same ownership rule as the database path.
        assertThat(read.get().userId()).isEqualTo("citizen-1");
    }

    @Test
    @DisplayName("Unreadable or absent cache content is a miss, never an error to the caller")
    void unreadableContent_isAMiss() {
        given(valueOperations.get(KEY)).willReturn("{not valid json");
        assertThat(cache.read("tx-1")).isEmpty();

        given(valueOperations.get(KEY)).willReturn(null);
        assertThat(cache.read("tx-1")).isEmpty();
    }

    @Test
    @DisplayName("Redis being unreachable degrades to a miss rather than failing the request")
    void redisUnavailable_degradesToMiss() {
        given(valueOperations.get(KEY))
                .willThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

        // Postgres remains the source of truth; a cache outage must not fail a citizen's status
        // request.
        assertThat(cache.read("tx-1")).isEmpty();
    }
}
