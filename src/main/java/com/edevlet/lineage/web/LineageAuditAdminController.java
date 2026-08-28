package com.edevlet.lineage.web;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/lineage/admin/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Admin Security Audit API", description = "Access Audit Trail Endpoints for Compliance and Identity Security Monitoring")
public class LineageAuditAdminController {

    private final LineageAuditLogRepository auditLogRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Query Security Audit Logs", description = "Retrieves citizen data access audit trail entries filtered by userId or transactionId.")
    public ResponseEntity<List<LineageAuditLog>> getAuditLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String transactionId) {

        log.info("Admin querying security audit logs for userId={}, transactionId={}", userId, transactionId);

        if (transactionId != null && !transactionId.isBlank()) {
            return ResponseEntity.ok(auditLogRepository.findByTransactionIdOrderByTimestampDesc(transactionId));
        }

        if (userId != null && !userId.isBlank()) {
            return ResponseEntity.ok(auditLogRepository.findByUserIdOrderByTimestampDesc(userId));
        }

        return ResponseEntity.ok(auditLogRepository.findAll());
    }
}
