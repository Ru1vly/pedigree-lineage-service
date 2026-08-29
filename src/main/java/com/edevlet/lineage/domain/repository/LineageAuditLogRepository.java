package com.edevlet.lineage.domain.repository;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LineageAuditLogRepository extends JpaRepository<LineageAuditLog, UUID> {

    /**
     * Unpaged read of one transaction's trail. A single query writes a bounded handful of audit
     * rows, so this one cannot run away the way an unfiltered read can; it is used by tests and by
     * internal callers that already know they are looking at one transaction.
     */
    List<LineageAuditLog> findByTransactionIdOrderByTimestampDesc(String transactionId);

    Page<LineageAuditLog> findByTransactionIdOrderByTimestampDesc(String transactionId, Pageable pageable);

    Page<LineageAuditLog> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    /**
     * Deliberately not {@code findAll(Pageable)}. The inherited method paginates in whatever order
     * the database returns rows, which for an audit trail is not an order at all: the same page
     * number can hand back different rows between calls, so a reader paging through the trail can
     * both miss rows and see others twice. Ordering by timestamp makes the pages mean something.
     */
    Page<LineageAuditLog> findAllByOrderByTimestampDesc(Pageable pageable);
}
