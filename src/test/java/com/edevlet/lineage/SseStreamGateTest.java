package com.edevlet.lineage;

import com.edevlet.lineage.config.SseProperties;
import com.edevlet.lineage.web.SseStreamGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admission control for the progress-stream endpoint.
 *
 * <p>The polls behind those streams run on one fixed-size scheduler. Without a ceiling the input
 * to that pool is set by client demand alone, and nothing errors when it is exceeded - the queue
 * just grows and every connected client's poll interval stretches while the endpoint carries on
 * advertising two seconds. These tests pin the two properties that make the limit real: it is
 * enforced exactly, and a slot always comes back.
 */
class SseStreamGateTest {

    private static SseStreamGate gateWithLimit(int limit) {
        SseProperties properties = new SseProperties();
        properties.setMaxConcurrentStreams(limit);
        return new SseStreamGate(properties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("Admits up to the configured limit and then refuses")
    void admitsUpToTheLimit() {
        SseStreamGate gate = gateWithLimit(3);

        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
        assertThat(gate.activeStreams()).isEqualTo(3);

        gate.release();
        assertThat(gate.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("The active count never exceeds the ceiling, even when connections race")
    void neverOvershootsUnderConcurrency() throws Exception {
        int limit = 8;
        int contenders = 64;
        SseStreamGate gate = gateWithLimit(limit);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch startTogether = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(contenders);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        startTogether.await();
                        if (gate.tryAcquire()) {
                            admitted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startTogether.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // "increment, then check and decrement on overshoot" would let the counter briefly exceed
        // the ceiling - and the gauge reports that counter.
        assertThat(admitted.get()).isEqualTo(limit);
        assertThat(gate.activeStreams()).isEqualTo(limit);
    }

    @Test
    @DisplayName("An over-release cannot drive the count negative and quietly raise the real ceiling")
    void overRelease_IsClamped() {
        SseStreamGate gate = gateWithLimit(2);

        gate.release();
        gate.release();

        assertThat(gate.activeStreams()).isZero();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("Saturation is observable, not something an operator has to infer from latency")
    void activeStreamsAreExposedAsAGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SseProperties properties = new SseProperties();
        properties.setMaxConcurrentStreams(5);
        SseStreamGate gate = new SseStreamGate(properties, registry);

        gate.tryAcquire();
        gate.tryAcquire();

        assertThat(registry.get("lineage.sse.streams.active").gauge().value()).isEqualTo(2.0);
    }
}
