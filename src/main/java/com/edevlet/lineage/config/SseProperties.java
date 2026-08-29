package com.edevlet.lineage.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bounds on the live progress-stream endpoint.
 *
 * <p>The stream is not a push: it is a repeating read of the task's status, published to the client
 * as Server-Sent Events. That is a deliberate trade - the phase transitions do exist as events on
 * Kafka, but those are consumed by the worker tier, and turning them into a fan-out an arbitrary
 * API pod can subscribe to per connection is a larger piece of architecture than this endpoint has
 * earned. The reads are cheap in practice because in-flight tasks are served from the Redis state
 * cache rather than Postgres.
 *
 * <p>What was not a trade was that the polls ran on a hard-coded four-thread scheduler with no
 * limit on the number of streams feeding it. Past a few hundred concurrent connections the queue
 * in front of those four threads grows without bound and the advertised two-second interval
 * silently becomes something else - the endpoint keeps promising a cadence it is no longer keeping.
 * The pool is sized from configuration now, and the number of concurrent streams is capped, so the
 * limit is refused at the door with a 503 and a {@code Retry-After} instead of being absorbed as
 * quietly growing latency for everyone already connected.
 */
@Component
@ConfigurationProperties(prefix = "app.sse")
@Getter
@Setter
public class SseProperties {

    /**
     * Concurrent progress streams this instance will serve. Beyond it, new stream requests are
     * refused; polling {@code GET /api/v1/lineage/queries/{id}} still works and is what a refused
     * client should fall back to.
     */
    private int maxConcurrentStreams = 200;

    /**
     * Threads shared by every open stream's poll. Rule of thumb: a poll is a cache read plus a
     * serialize, so one thread carries a lot of streams at a two-second interval - but not an
     * unlimited number, which is why {@link #maxConcurrentStreams} exists alongside it.
     */
    private int schedulerPoolSize = 8;

    private Duration pollInterval = Duration.ofSeconds(2);

    private Duration streamTimeout = Duration.ofSeconds(60);
}
