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

    /**
     * Set only on a re-queued attempt: the earliest instant a worker should start this attempt.
     *
     * <p>A pipeline retry is published as a fresh outbox row, which Debezium tails off the WAL and
     * hands to Kafka within milliseconds - so before this field existed, a failing task's next
     * attempt hit the legacy census backend essentially instantly, and the only thing standing
     * between a struggling backend and the full retry budget fired back-to-back was the circuit
     * breaker. There is no delay to be had from the outbox itself (its whole point is that it
     * publishes as soon as the transaction commits), so the wait is applied by the consumer.
     *
     * <p>Null on an initial submission, and on any message enqueued before this field existed;
     * both mean "start now".
     */
    private Instant retryNotBefore;

    /** 1 for the first re-queued attempt. 0/absent on an initial submission. */
    private int retryAttempt;
}
