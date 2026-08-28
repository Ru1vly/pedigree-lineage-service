package com.edevlet.lineage.domain.repository;

import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LineageQueryRepository extends JpaRepository<LineageQueryTask, UUID> {

    Optional<LineageQueryTask> findByTransactionId(String transactionId);

    // Idempotency keys are only guaranteed unique per user (see
    // V3__scope_idempotency_key_to_user.sql) - always look them up scoped to the caller so one
    // user can never be handed back another user's task via a colliding or reused key.
    Optional<LineageQueryTask> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    List<LineageQueryTask> findByUserIdOrderByCreatedAtDesc(String userId);

    List<LineageQueryTask> findByStatusAndUpdatedAtBefore(TaskStatus status, Instant cutoffTime);

    @Modifying
    @Query("UPDATE LineageQueryTask t SET t.status = :status, t.currentPhase = :phase, t.progressPercentage = :progress, t.updatedAt = :now WHERE t.transactionId = :transactionId")
    int updateTaskProgress(@Param("transactionId") String transactionId,
                           @Param("status") TaskStatus status,
                           @Param("phase") com.edevlet.lineage.domain.model.ProcessingPhase phase,
                           @Param("progress") int progress,
                           @Param("now") Instant now);
}
