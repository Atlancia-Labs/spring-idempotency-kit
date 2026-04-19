package com.atlancia.idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.Callable;

public class IdempotencyMetrics {

    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Counter lockAcquiredCounter;
    private final Counter lockRejectedCounter;
    private final Timer executionTimer;
    private final MeterRegistry registry;
    private final String keyPrefix;

    public IdempotencyMetrics(MeterRegistry registry, String keyPrefix) {
        this.registry = registry;
        this.keyPrefix = keyPrefix;
        this.cacheHitCounter = Counter.builder("idempotency.cache.hit")
                .tag("key_prefix", keyPrefix)
                .register(registry);
        this.cacheMissCounter = Counter.builder("idempotency.cache.miss")
                .tag("key_prefix", keyPrefix)
                .register(registry);
        this.lockAcquiredCounter = Counter.builder("idempotency.lock.acquired")
                .tag("key_prefix", keyPrefix)
                .register(registry);
        this.lockRejectedCounter = Counter.builder("idempotency.lock.rejected")
                .tag("key_prefix", keyPrefix)
                .register(registry);
        this.executionTimer = Timer.builder("idempotency.execution")
                .tag("key_prefix", keyPrefix)
                .register(registry);
    }

    public void recordCacheHit() {
        cacheHitCounter.increment();
    }

    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }

    public void recordLockAcquired() {
        lockAcquiredCounter.increment();
    }

    public void recordLockRejected() {
        lockRejectedCounter.increment();
    }

    public void recordFailOpen(String phase) {
        Counter.builder("idempotency.failopen")
                .tag("key_prefix", keyPrefix)
                .tag("phase", phase)
                .register(registry)
                .increment();
    }

    public void recordConflict(String strategy) {
        Counter.builder("idempotency.conflict")
                .tag("key_prefix", keyPrefix)
                .tag("strategy", strategy)
                .register(registry)
                .increment();
    }

    public <T> T recordExecution(Callable<T> action) throws Exception {
        return executionTimer.recordCallable(action);
    }
}
