package com.edevlet.lineage.web;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.dto.AuditLogEntryResponse;
import com.edevlet.lineage.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read access to the compliance audit trail, for operators.
 *
 * <p>Two things this endpoint deliberately does not do, both of which it used to:
 *
 * <ul>
 *   <li><b>It does not return national IDs.</b> It returned the {@link LineageAuditLog} entity
 *       straight out of the repository, and that entity's {@code nationalId} is decrypted by
 *       Hibernate on load, so the JSON carried every citizen's TCKN in cleartext. Responses are
 *       built from {@link AuditLogEntryResponse}, which masks. See that type for why masking is
 *       the only mode.
 *   <li><b>It does not read the whole table.</b> With no filter it fell through to
 *       {@code findAll()}. The trail grows by two or more rows per submitted query and is never
 *       trimmed, so that is an unbounded result set - an out-of-memory on the API pod on a table
 *       that has been running for a week, and the single request that exfiltrates the entire
 *       trail. Every path here is paged, and the page size is capped server-side rather than
 *       trusted from the query string.
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/lineage/admin/audit-logs")
@RequiredArgsConstructor
@Tag(name = "Admin Security Audit API", description = "Access Audit Trail Endpoints for Compliance and Identity Security Monitoring")
public class LineageAuditAdminController {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private final LineageAuditLogRepository auditLogRepository;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Query Security Audit Logs",
            description = "Retrieves a page of citizen data access audit trail entries, optionally filtered by "
                    + "userId or transactionId. National identity numbers are masked; page size is capped at "
                    + MAX_PAGE_SIZE + ".")
    public ResponseEntity<PagedResponse<AuditLogEntryResponse>> getAuditLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String transactionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        Pageable pageable = boundedPageRequest(page, size);

        log.info("Admin querying security audit logs for userId={}, transactionId={}, page={}, size={}",
                userId, transactionId, pageable.getPageNumber(), pageable.getPageSize());

        Page<LineageAuditLog> resultPage = findPage(userId, transactionId, pageable);
        return ResponseEntity.ok(PagedResponse.from(resultPage, AuditLogEntryResponse::from));
    }

    private Page<LineageAuditLog> findPage(String userId, String transactionId, Pageable pageable) {
        if (transactionId != null && !transactionId.isBlank()) {
            return auditLogRepository.findByTransactionIdOrderByTimestampDesc(transactionId, pageable);
        }
        if (userId != null && !userId.isBlank()) {
            return auditLogRepository.findByUserIdOrderByTimestampDesc(userId, pageable);
        }
        return auditLogRepository.findAllByOrderByTimestampDesc(pageable);
    }

    /**
     * Clamps rather than rejects. A caller asking for 100000 rows gets the largest page this
     * service is willing to serve, not a 400 - and, importantly, not 100000 rows.
     */
    private Pageable boundedPageRequest(int page, int size) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return PageRequest.of(boundedPage, boundedSize);
    }
}
