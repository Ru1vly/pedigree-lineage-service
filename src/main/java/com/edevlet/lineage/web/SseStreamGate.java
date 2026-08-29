package com.edevlet.lineage.web;

import com.edevlet.lineage.config.SseProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission control for the SSE progress endpoint, and the gauge that makes its saturation visible.
 *
 * <p>Every open stream contributes a repeating task to one shared, fixed-size scheduler. Without a
 * limit, the number of those tasks is set by client demand alone, and the failure mode is not an
 * error anyone sees - it is the poll interval stretching for every connected client at once while
 * the endpoint carries on advertising two seconds. Refusing the connection that would cross the
 * line converts that into something a caller can handle and an operator can alert on.
 *
 * <p>{@code lineage.sse.streams.active} against {@code app.sse.max-concurrent-streams} is the
 * signal to scale out (or to raise the ceiling deliberately, having looked at the pool).
 */
@Slf4j
@Component
public class SseStreamGate {

    private final AtomicInteger activeStreams = new AtomicInteger();
    private final int maxConcurrentStreams;

    public SseStreamGate(SseProperties sseProperties, MeterRegistry meterRegistry) {
        this.maxConcurrentStreams = sseProperties.getMaxConcurrentStreams();
        Gauge.builder("lineage.sse.streams.active", activeStreams, AtomicInteger::get)
                .description("Currently open Server-Sent Events progress streams on this instance")
                .tag("service", "pedigree-lineage-service")
                .register(meterRegistry);
    }

    /**
     * Reserves a slot, or reports that the instance is full. Uses a compare-and-set loop rather
     * than "increment then check and decrement on overshoot": the naive version lets the counter
     * briefly exceed the ceiling, which is what the gauge would then report.
     */
    public boolean tryAcquire() {
        while (true) {
            int current = activeStreams.get();
            if (current >= maxConcurrentStreams) {
                return false;
            }
            if (activeStreams.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /**
     * Releases a slot. Safe to call more than once for the same stream: SseEmitter can fire both
     * {@code onCompletion} and {@code onError} for one connection, and a double decrement would
     * leak capacity permanently. The caller guards with a compare-and-set of its own; the floor
     * here is the backstop.
     */
    public void release() {
        int remaining = activeStreams.decrementAndGet();
        if (remaining < 0) {
            log.warn("SSE stream slot released more times than acquired; clamping the active count to zero.");
            activeStreams.compareAndSet(remaining, 0);
        }
    }

    public int activeStreams() {
        return activeStreams.get();
    }

    public int maxConcurrentStreams() {
        return maxConcurrentStreams;
    }
}
