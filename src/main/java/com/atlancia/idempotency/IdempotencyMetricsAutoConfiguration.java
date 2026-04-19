package com.atlancia.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyMetrics idempotencyMetrics(MeterRegistry registry,
                                                  IdempotencyProperties properties) {
        return new IdempotencyMetrics(registry, properties.getKeyPrefix());
    }
}
