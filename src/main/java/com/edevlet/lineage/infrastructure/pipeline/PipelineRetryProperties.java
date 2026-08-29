package com.edevlet.lineage.infrastructure.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The pipeline's retry budget, in one place, with the arithmetic written down.
 *
 * <p>It was previously two hardcoded numbers in unrelated files plus a third that did not exist.
 * {@code LineageQueryService} stamped {@code maxRetries = 3} on every task;
 * {@code LegacyCensusGraphClientImpl} carried {@code @Retry(name = "legacyCensusBackend")} with no
 * matching {@code resilience4j.retry} block, so it silently ran on the library default of 3
 * attempts; and a re-queued attempt was published to the outbox with no delay at all, which
 * Debezium turns into a Kafka record within milliseconds.
 *
 * <p>Multiplied out, one task could put <b>up to twelve calls</b> into a legacy census backend that
 * was already failing, effectively back-to-back, with the circuit breaker as the only thing
 * absorbing them. The retry layers were each individually reasonable and nobody had multiplied
 * them together.
 *
 * <p>The budget now, with the defaults here and {@code resilience4j.retry.instances
 * .legacyCensusBackend.maxAttempts = 2}:
 *
 * <pre>
 *   pipeline attempts   = 1 + max-retries        = 3
 *   census calls each   = resilience4j maxAttempts = 2
 *   worst case          = 3 x 2                  = 6 calls per task
 *   spread over         ~ 2s + 6s of pipeline backoff, plus 500ms/1s inside each attempt
 * </pre>
 *
 * <p>Both multiplicands are configuration, so the product can be reasoned about and changed
 * deliberately rather than discovered.
 */
@Component
@ConfigurationProperties(prefix = "app.pipeline.retry")
@Getter
@Setter
public class PipelineRetryProperties {

    /** Re-queued attempts after the first. Total pipeline attempts is this plus one. */
    private int maxRetries = 2;

    /** Delay before the first re-queued attempt. Grows by {@link #backoffMultiplier} thereafter. */
    private Duration initialBackoff = Duration.ofSeconds(2);

    private double backoffMultiplier = 3.0;

    private Duration maxBackoff = Duration.ofSeconds(10);

    /**
     * Hard cap on how long a Kafka listener thread will wait for a deferred attempt.
     *
     * <p>The wait is taken on the consumer because there is nowhere else to take it: the outbox
     * publishes as soon as its transaction commits, by design. That does hold a listener thread,
     * which is why it is capped - and why the cap sits above {@link #maxBackoff}, so under the
     * default configuration it never truncates a backoff, only bounds the damage if the values are
     * tuned up carelessly or a message arrives with a far-future timestamp. A delay topic is the
     * scale-up answer if the retry budget ever needs to grow past seconds.
     */
    private Duration maxConsumerDeferral = Duration.ofSeconds(15);

    /**
     * Backoff before the given re-queued attempt (1 = the first retry), capped at
     * {@link #maxBackoff}.
     */
    public Duration backoffForAttempt(int retryAttempt) {
        if (retryAttempt <= 0) {
            return Duration.ZERO;
        }
        double scaled = initialBackoff.toMillis() * Math.pow(backoffMultiplier, retryAttempt - 1.0);
        long cappedMillis = (long) Math.min(scaled, maxBackoff.toMillis());
        return Duration.ofMillis(Math.max(0, cappedMillis));
    }
}
