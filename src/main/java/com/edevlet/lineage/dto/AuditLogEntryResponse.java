package com.edevlet.lineage.dto;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.infrastructure.util.NationalIdMasker;

import java.time.Instant;
import java.util.UUID;

/**
 * The admin audit trail as it leaves the process.
 *
 * <p>The admin endpoint used to return {@link LineageAuditLog} - the JPA entity - directly. That
 * entity's {@code nationalId} carries {@code @Convert(TcknAttributeConverter.class)}, so Hibernate
 * decrypts it on load, and Jackson then serialised the decrypted value. One authenticated ADMIN GET
 * returned every citizen's TCKN in cleartext JSON, which made the field-level encryption at rest
 * moot: an attacker who reaches an admin token no longer needs the database or the master key.
 *
 * <p>A projection is what fixes that, not a {@code @JsonIgnore} on the entity alone. The entity is
 * annotated too (defence in depth, so a future endpoint cannot leak it by accident), but the rule
 * this type exists to enforce is that the audit API has no unmasked mode at all - masking is not a
 * flag an operator can turn off, because there is no read path that produces the full value.
 *
 * <p>An audit trail's job is answering "who looked at whose record, when". The first three and last
 * three digits are enough to correlate a row against a TCKN an investigator already holds, and not
 * enough to enumerate citizens out of the trail. Where a full value is genuinely required - a
 * prosecutor's order - it is read from the database under separate authorisation and a separate
 * key, which is the control the encryption was bought for.
 */
public record AuditLogEntryResponse(
        UUID id,
        String transactionId,
        String userId,
        String nationalIdMasked,
        String action,
        String ipAddress,
        String userAgent,
        Instant timestamp,
        String details) {

    public static AuditLogEntryResponse from(LineageAuditLog auditLog) {
        return new AuditLogEntryResponse(
                auditLog.getId(),
                auditLog.getTransactionId(),
                auditLog.getUserId(),
                NationalIdMasker.mask(auditLog.getNationalId()),
                auditLog.getAction(),
                auditLog.getIpAddress(),
                auditLog.getUserAgent(),
                auditLog.getTimestamp(),
                auditLog.getDetails());
    }
}
