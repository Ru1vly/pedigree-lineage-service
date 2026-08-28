package com.edevlet.lineage;

import com.edevlet.lineage.config.TestConfig;
import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class FieldLevelEncryptionJpaTest {

    @Autowired
    private LineageQueryRepository lineageQueryRepository;

    @Autowired
    private LineageAuditLogRepository lineageAuditLogRepository;

    @Autowired
    private TcknEncryptionService encryptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Should persist encrypted TCKN in database ensuring DBAs cannot read unmasked identity data")
    void shouldPersistEncryptedTcknInPostgresDb() {
        String rawTckn = "12345678950";
        String txId = "tx-enc-test-" + UUID.randomUUID();
        String idempotencyKey = "idempotency-enc-" + UUID.randomUUID();

        LineageQueryTask task = LineageQueryTask.builder()
                .transactionId(txId)
                .idempotencyKey(idempotencyKey)
                .nationalId(rawTckn)
                .userId("user-dba-security-test")
                .status(TaskStatus.SUBMITTED)
                .generationsDepth(3)
                .includeCertificates(false)
                .documentFormat("PDF")
                .requestPayload("{}")
                .retryCount(0)
                .maxRetries(3)
                .build();

        LineageQueryTask saved = lineageQueryRepository.saveAndFlush(task);

        // 1. Direct JDBC query mimicking DBA direct SQL access to PostgreSQL table
        String rawDbColumnValue = jdbcTemplate.queryForObject(
                "SELECT national_id FROM lineage_queries WHERE id = ?",
                String.class,
                saved.getId()
        );

        assertThat(rawDbColumnValue).isNotNull();
        // DBA query must NOT see raw unmasked citizen TCKN
        assertThat(rawDbColumnValue).isNotEqualTo(rawTckn);
        // Column must contain encrypted ciphertext payload
        assertThat(encryptionService.isEncrypted(rawDbColumnValue)).isTrue();

        // 2. Application entity fetch must transparently decrypt back to original TCKN
        LineageQueryTask fetched = lineageQueryRepository.findById(saved.getId()).orElseThrow();
        assertThat(fetched.getNationalId()).isEqualTo(rawTckn);
    }

    @Test
    @DisplayName("Should persist encrypted TCKN in security audit log database table")
    void shouldEncryptTcknInAuditLogTable() {
        String rawTckn = "98765432100";
        String txId = "tx-audit-enc-" + UUID.randomUUID();

        LineageAuditLog auditLog = LineageAuditLog.builder()
                .transactionId(txId)
                .userId("auditor-user-456")
                .nationalId(rawTckn)
                .action("LINEAGE_QUERY_REQUESTED")
                .ipAddress("192.168.1.100")
                .userAgent("Mozilla/5.0")
                .details("Field encryption audit check")
                .build();

        LineageAuditLog savedAudit = lineageAuditLogRepository.saveAndFlush(auditLog);

        // Direct SQL query mimicking DBA access
        String rawDbAuditColumn = jdbcTemplate.queryForObject(
                "SELECT national_id FROM lineage_audit_logs WHERE id = ?",
                String.class,
                savedAudit.getId()
        );

        assertThat(rawDbAuditColumn).isNotEqualTo(rawTckn);
        assertThat(encryptionService.isEncrypted(rawDbAuditColumn)).isTrue();

        // Application retrieval
        LineageAuditLog fetchedAudit = lineageAuditLogRepository.findById(savedAudit.getId()).orElseThrow();
        assertThat(fetchedAudit.getNationalId()).isEqualTo(rawTckn);
    }
}
