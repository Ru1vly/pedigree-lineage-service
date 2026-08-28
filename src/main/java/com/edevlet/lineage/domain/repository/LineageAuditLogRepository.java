package com.edevlet.lineage.domain.repository;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LineageAuditLogRepository extends JpaRepository<LineageAuditLog, UUID> {
    List<LineageAuditLog> findByUserIdOrderByTimestampDesc(String userId);
    List<LineageAuditLog> findByTransactionIdOrderByTimestampDesc(String transactionId);
}
