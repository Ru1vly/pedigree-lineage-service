package com.edevlet.lineage.domain.model;

import com.edevlet.lineage.infrastructure.security.encryption.TcknAttributeConverter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lineage_audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LineageAuditLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    /**
     * Decrypted by {@link TcknAttributeConverter} on load, so it holds the citizen's real TCKN in
     * memory. {@code @JsonIgnore} is the backstop that keeps it from leaving the process if this
     * entity is ever handed to Jackson: the admin audit endpoint did exactly that and served the
     * whole trail in cleartext. The controller projects to a masked DTO; this annotation is what
     * makes the next accidental {@code ResponseEntity.ok(entity)} harmless rather than a breach.
     */
    @JsonIgnore
    @Convert(converter = TcknAttributeConverter.class)
    @Column(name = "national_id", nullable = false, length = 512)
    private String nationalId;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
