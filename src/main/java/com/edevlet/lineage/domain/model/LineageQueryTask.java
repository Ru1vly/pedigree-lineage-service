package com.edevlet.lineage.domain.model;

import com.edevlet.lineage.infrastructure.security.encryption.TcknAttributeConverter;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lineage_queries",
        uniqueConstraints = @UniqueConstraint(name = "uq_lineage_queries_user_idempotency",
                columnNames = {"user_id", "idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LineageQueryTask {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 64)
    private String transactionId;

    // Uniqueness is per (userId, idempotencyKey) - see the class-level @Table uniqueConstraints
    // - not globally per key, so one caller's idempotency key can never collide with another's.
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Convert(converter = TcknAttributeConverter.class)
    @Column(name = "national_id", nullable = false, length = 512)
    private String nationalId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "user_roles", length = 256)
    private String userRoles;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_phase", length = 64)
    private ProcessingPhase currentPhase;

    @Column(name = "progress_percentage", nullable = false)
    private int progressPercentage;

    @Column(name = "generations_depth", nullable = false)
    private int generationsDepth;

    @Column(name = "include_certificates", nullable = false)
    private boolean includeCertificates;

    @Column(name = "document_format", nullable = false, length = 16)
    private String documentFormat;

    @Column(name = "request_payload", nullable = false, columnDefinition = "TEXT")
    private String requestPayload;

    @Column(name = "result_payload", columnDefinition = "TEXT")
    private String resultPayload;

    @Column(name = "result_download_url", length = 512)
    private String resultDownloadUrl;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = TaskStatus.SUBMITTED;
        }
        if (currentPhase == null) {
            currentPhase = ProcessingPhase.INITIATED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
