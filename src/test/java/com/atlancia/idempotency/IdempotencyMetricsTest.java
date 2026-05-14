package com.atlancia.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

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

    @Test
    void recordExecutionError_incrementsCounterWithException() {
        metrics.recordExecutionError("NullPointerException");
        assertThat(registry.counter("idempotency.execution.error",
                "key_prefix", "idempotency:", "exception", "NullPointerException").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordLockReleaseFailure_incrementsCounter() {
        metrics.recordLockReleaseFailure();
        assertThat(registry.counter("idempotency.lock.release.failure", "key_prefix", "idempotency:").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordStorageError_incrementsCounterWithPhase() {
        metrics.recordStorageError("cache");
        metrics.recordStorageError("lock");
        metrics.recordStorageError("store");
        assertThat(registry.counter("idempotency.storage.error",
                "key_prefix", "idempotency:", "phase", "cache").count()).isEqualTo(1.0);
        assertThat(registry.counter("idempotency.storage.error",
                "key_prefix", "idempotency:", "phase", "lock").count()).isEqualTo(1.0);
        assertThat(registry.counter("idempotency.storage.error",
                "key_prefix", "idempotency:", "phase", "store").count()).isEqualTo(1.0);
    }

    @Test
    void recordSerializationError_incrementsCounter() {
        metrics.recordSerializationError();
        assertThat(registry.counter("idempotency.serialization.error", "key_prefix", "idempotency:").count())
                .isEqualTo(1.0);
    }

    @Test
    void waitDurationTimer_recordsDuration() {
        Object sample = metrics.startWaitTimer();
        metrics.stopWaitTimer(sample);
        assertThat(registry.timer("idempotency.wait.duration", "key_prefix", "idempotency:").count())
                .isEqualTo(1);
    }

    @Test
    void registerKeyCountGauge_registersGauge() {
        IdempotencyStorage storage = new IdempotencyStorage() {
            @Override public Optional<IdempotencyResult> get(String key) { return Optional.empty(); }
            @Override public String acquireLock(String key, Duration lockTtl) { return null; }
            @Override public void store(String key, IdempotencyResult result, Duration ttl) {}
            @Override public void releaseLock(String key, String lockToken) {}
            @Override public long keyCount() { return 42; }
        };
        metrics.registerKeyCountGauge(storage);
        assertThat(registry.get("idempotency.keys.count").gauge().value()).isEqualTo(42.0);
    }
}
