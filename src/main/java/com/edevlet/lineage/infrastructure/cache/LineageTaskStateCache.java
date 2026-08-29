package com.edevlet.lineage.infrastructure.cache;

import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Both ends of the {@code state:lineage:*} cache the documentation calls "a state cache for
 * ultra-fast polling".
 *
 * <p>It previously had one end. LineagePipelinePhaseRunner wrote a JSON blob under that key on
 * every phase transition and nothing anywhere read it back - status polling went to Postgres on
 * every request, including the SSE stream's poll loop. The cache cost a Redis round trip per phase
 * and bought nothing - documented infrastructure that was never wired to anything.
 *
 * <p>Two deliberate constraints on what is cached:
 *
 * <ul>
 *   <li><b>Written after commit, never inside the transaction.</b> The old write fired mid-phase,
 *       so a transaction that later rolled back left the cache asserting progress that no longer
 *       existed in the database - and once a reader existed, that would be served to callers. The
 *       write is registered as an after-commit callback, so the cache can only ever describe state
 *       that actually committed.
 *   <li><b>Progress fields only, not the result payload.</b> Reads for a COMPLETED task go to
 *       Postgres, which owns the ancestry result and the audit trail. The cache exists for the
 *       in-flight polling path, which is the one that is hot.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineageTaskStateCache {

    static final String STATE_PREFIX = "state:lineage:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * The cached projection. {@code userId} is carried so a reader can enforce the same ownership
     * rule as the database path - a cache that skips the authorization check is a way to read
     * another citizen's task.
     */
    public record CachedTaskState(
            String transactionId,
            String userId,
            TaskStatus status,
            ProcessingPhase currentPhase,
            int progressPercentage,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            String resultDownloadUrl,
            String errorCode,
            String errorMessage) {}

    /**
     * Publishes a committed phase transition to the cache, after the current transaction commits.
     * Falls back to acting immediately when called with no transaction active, so the method is
     * safe either way.
     *
     * <p>Reaching a terminal status <b>evicts</b> rather than writes. Readers go to Postgres for
     * terminal tasks regardless, so there is nothing to gain by caching one - and something real
     * to lose: if the cache write for the final transition failed (Redis briefly unavailable for
     * writes but not for reads), a stale non-terminal entry would pin a finished task at 70% for
     * the full hour of its TTL, and every poll would keep reporting it as still running. Deleting
     * is the operation whose failure mode is harmless: a miss just falls through to the database.
     */
    public void writeAfterCommit(LineageQueryTask task) {
        if (task.getStatus() != null && task.getStatus().isTerminal()) {
            String transactionId = task.getTransactionId();
            runAfterCommit(() -> evict(transactionId));
            return;
        }

        CachedTaskState snapshot = snapshotOf(task);
        runAfterCommit(() -> write(snapshot));
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    public Optional<CachedTaskState> read(String transactionId) {
        try {
            String cached = redisTemplate.opsForValue().get(STATE_PREFIX + transactionId);
            if (cached == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(cached, CachedTaskState.class));
        } catch (Exception cacheFailure) {
            // A cache is an optimisation; Postgres remains the source of truth. Never fail a
            // citizen's status request because Redis is unreachable or holds a stale shape.
            log.warn("Ignoring unreadable cached state for transactionId={}: {}",
                    transactionId, cacheFailure.getMessage());
            return Optional.empty();
        }
    }

    public void evict(String transactionId) {
        try {
            redisTemplate.delete(STATE_PREFIX + transactionId);
        } catch (Exception cacheFailure) {
            log.warn("Failed to evict cached state for transactionId={}: {}",
                    transactionId, cacheFailure.getMessage());
        }
    }

    private void write(CachedTaskState snapshot) {
        try {
            redisTemplate.opsForValue().set(
                    STATE_PREFIX + snapshot.transactionId(),
                    objectMapper.writeValueAsString(snapshot),
                    TTL);
        } catch (Exception cacheFailure) {
            log.warn("Failed to cache state for transactionId={}: {}",
                    snapshot.transactionId(), cacheFailure.getMessage());
        }
    }

    private CachedTaskState snapshotOf(LineageQueryTask task) {
        return new CachedTaskState(
                task.getTransactionId(),
                task.getUserId(),
                task.getStatus(),
                task.getCurrentPhase(),
                task.getProgressPercentage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                task.getResultDownloadUrl(),
                task.getErrorCode(),
                task.getErrorMessage());
    }
}
