package com.atlancia.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@ConfigurationProperties("spring.idempotency")
public class IdempotencyProperties {

    private long defaultTtl = 24;
    private TimeUnit defaultTimeUnit = TimeUnit.HOURS;
    private ConcurrentStrategy defaultOnConcurrent = ConcurrentStrategy.REJECT;
    private String keyPrefix = "idempotency:";
    private Duration lockTimeout = Duration.ofSeconds(30);
    private Duration waitTimeout = Duration.ofSeconds(10);
    private Duration waitPollInitialInterval = Duration.ofMillis(100);
    private Duration waitPollMaxInterval = Duration.ofSeconds(1);
    private FailureStrategy defaultOnFailure = FailureStrategy.FAIL_OPEN;

    public long getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(long defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public TimeUnit getDefaultTimeUnit() {
        return defaultTimeUnit;
    }

    public void setDefaultTimeUnit(TimeUnit defaultTimeUnit) {
        this.defaultTimeUnit = defaultTimeUnit;
    }

    public ConcurrentStrategy getDefaultOnConcurrent() {
        return defaultOnConcurrent;
    }

    public void setDefaultOnConcurrent(ConcurrentStrategy defaultOnConcurrent) {
        this.defaultOnConcurrent = defaultOnConcurrent;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getLockTimeout() {
        return lockTimeout;
    }

    public void setLockTimeout(Duration lockTimeout) {
        this.lockTimeout = lockTimeout;
    }

    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    public void setWaitTimeout(Duration waitTimeout) {
        this.waitTimeout = waitTimeout;
    }

    public Duration getWaitPollInitialInterval() {
        return waitPollInitialInterval;
    }

    public void setWaitPollInitialInterval(Duration waitPollInitialInterval) {
        this.waitPollInitialInterval = waitPollInitialInterval;
    }

    public Duration getWaitPollMaxInterval() {
        return waitPollMaxInterval;
    }

    public void setWaitPollMaxInterval(Duration waitPollMaxInterval) {
        this.waitPollMaxInterval = waitPollMaxInterval;
    }

    /**
     * @deprecated Use {@link #setWaitPollInitialInterval(Duration)} and
     * {@link #setWaitPollMaxInterval(Duration)} instead.
     */
    @Deprecated(since = "0.2.0", forRemoval = true)
    public void setWaitPollInterval(Duration waitPollInterval) {
        this.waitPollInitialInterval = waitPollInterval;
        this.waitPollMaxInterval = waitPollInterval;
    }

    public Duration getResolvedTtl(long annotationTtl, TimeUnit annotationTimeUnit) {
        if (annotationTtl > 0) {
            return Duration.of(annotationTtl, annotationTimeUnit.toChronoUnit());
        }
        return Duration.of(defaultTtl, defaultTimeUnit.toChronoUnit());
    }

    public ConcurrentStrategy getResolvedConcurrentStrategy(ConcurrentStrategy annotationStrategy) {
        if (annotationStrategy != ConcurrentStrategy.DEFAULT) {
            return annotationStrategy;
        }
        return defaultOnConcurrent;
    }

    public FailureStrategy getDefaultOnFailure() {
        return defaultOnFailure;
    }

    public void setDefaultOnFailure(FailureStrategy defaultOnFailure) {
        this.defaultOnFailure = defaultOnFailure;
    }

    public FailureStrategy getResolvedFailureStrategy(FailureStrategy annotationStrategy) {
        if (annotationStrategy != FailureStrategy.DEFAULT) {
            return annotationStrategy;
        }
        return defaultOnFailure;
    }
}
