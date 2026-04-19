package com.atlancia.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyMetricsTest {

    private MeterRegistry registry;
    private IdempotencyMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IdempotencyMetrics(registry, "idempotency:");
    }

    @Test
    void recordCacheHit_incrementsCounter() {
        metrics.recordCacheHit();
        assertThat(registry.counter("idempotency.cache.hit", "key_prefix", "idempotency:").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordCacheMiss_incrementsCounter() {
        metrics.recordCacheMiss();
        assertThat(registry.counter("idempotency.cache.miss", "key_prefix", "idempotency:").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordLockAcquired_incrementsCounter() {
        metrics.recordLockAcquired();
        assertThat(registry.counter("idempotency.lock.acquired", "key_prefix", "idempotency:").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordLockRejected_incrementsCounter() {
        metrics.recordLockRejected();
        assertThat(registry.counter("idempotency.lock.rejected", "key_prefix", "idempotency:").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordFailOpen_incrementsCounterWithPhase() {
        metrics.recordFailOpen("cache");
        assertThat(registry.counter("idempotency.failopen", "key_prefix", "idempotency:", "phase", "cache").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordConflict_incrementsCounterWithStrategy() {
        metrics.recordConflict("reject");
        assertThat(registry.counter("idempotency.conflict", "key_prefix", "idempotency:", "strategy", "reject").count())
                .isEqualTo(1.0);
    }

    @Test
    void executionTimer_recordsDuration() throws Exception {
        String result = metrics.recordExecution(() -> "done");
        assertThat(result).isEqualTo("done");
        assertThat(registry.timer("idempotency.execution", "key_prefix", "idempotency:").count())
                .isEqualTo(1);
    }
}
