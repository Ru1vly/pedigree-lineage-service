package com.edevlet.lineage.infrastructure.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageQueryMessage implements Serializable {
    private String transactionId;
    private String userId;
    private String nationalId;
    private int generationsDepth;
    private boolean includeCertificates;
    private String documentFormat;
    private String idempotencyKey;
    private String traceId;
    private Instant submittedAt;

    /**
     * The submitting citizen's origin IP and user agent, captured at the HTTP edge and carried
     * across the outbox so worker-side audit rows can record who the request actually came from.
     *
     * <p>Without these the worker had nothing to write and stamped the literal
     * {@code "SYSTEM_KAFKA_WORKER"} into {@code lineage_audit_logs.ip_address}. Every audit row
     * written on the asynchronous half of the pipeline - which is where the lineage query is
     * actually executed - therefore recorded the worker instead of the citizen, and the origin IP
     * reached the compliance trail only for the initial synchronous submit.
     *
     * <p>Null for any message enqueued before this field existed; the consumer degrades to an
     * explicit unknown marker rather than inventing a value.
     */
    private String clientIpAddress;
    private String clientUserAgent;
}
