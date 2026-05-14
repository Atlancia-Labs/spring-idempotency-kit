package com.atlancia.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyPropertiesTest {

    private final IdempotencyProperties properties = new IdempotencyProperties();

    @SuppressWarnings("removal")
    @Test
    void deprecatedSetWaitPollInterval_setsBothNewProperties() {
        properties.setWaitPollInterval(Duration.ofMillis(300));

        assertThat(properties.getWaitPollInitialInterval()).isEqualTo(Duration.ofMillis(300));
        assertThat(properties.getWaitPollMaxInterval()).isEqualTo(Duration.ofMillis(300));
    }

    @Test
    void resolvedTtl_usesAnnotationValueWhenPositive() {
        Duration resolved = properties.getResolvedTtl(5, TimeUnit.MINUTES);

        assertThat(resolved).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void resolvedTtl_usesDefaultWhenAnnotationIsNegative() {
        Duration resolved = properties.getResolvedTtl(-1, TimeUnit.MINUTES);

        assertThat(resolved).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void resolvedConcurrentStrategy_usesAnnotationWhenNotDefault() {
        ConcurrentStrategy resolved = properties.getResolvedConcurrentStrategy(ConcurrentStrategy.WAIT);

        assertThat(resolved).isEqualTo(ConcurrentStrategy.WAIT);
    }

    @Test
    void resolvedConcurrentStrategy_usesGlobalDefaultWhenDefault() {
        properties.setDefaultOnConcurrent(ConcurrentStrategy.WAIT);

        ConcurrentStrategy resolved = properties.getResolvedConcurrentStrategy(ConcurrentStrategy.DEFAULT);

        assertThat(resolved).isEqualTo(ConcurrentStrategy.WAIT);
    }

    @Test
    void resolvedFailureStrategy_usesAnnotationWhenNotDefault() {
        FailureStrategy resolved = properties.getResolvedFailureStrategy(FailureStrategy.FAIL_CLOSED);

        assertThat(resolved).isEqualTo(FailureStrategy.FAIL_CLOSED);
    }

    @Test
    void resolvedFailureStrategy_usesGlobalDefaultWhenDefault() {
        properties.setDefaultOnFailure(FailureStrategy.FAIL_CLOSED);

        FailureStrategy resolved = properties.getResolvedFailureStrategy(FailureStrategy.DEFAULT);

        assertThat(resolved).isEqualTo(FailureStrategy.FAIL_CLOSED);
    }

    @Test
    void defaults_areCorrect() {
        assertThat(properties.getDefaultTtl()).isEqualTo(24);
        assertThat(properties.getDefaultTimeUnit()).isEqualTo(TimeUnit.HOURS);
        assertThat(properties.getDefaultOnConcurrent()).isEqualTo(ConcurrentStrategy.REJECT);
        assertThat(properties.getDefaultOnFailure()).isEqualTo(FailureStrategy.FAIL_OPEN);
        assertThat(properties.getKeyPrefix()).isEqualTo("idempotency:");
        assertThat(properties.getLockTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getWaitTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getWaitPollInitialInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.getWaitPollMaxInterval()).isEqualTo(Duration.ofSeconds(1));
    }
}
