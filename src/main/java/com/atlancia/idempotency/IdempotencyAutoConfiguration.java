package com.atlancia.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyStorage idempotencyStorage(StringRedisTemplate redisTemplate,
                                                  ObjectMapper objectMapper,
                                                  IdempotencyProperties properties) {
        return new RedisIdempotencyStorage(redisTemplate, objectMapper, properties.getKeyPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyAspect idempotencyAspect(IdempotencyStorage storage,
                                                IdempotencyProperties properties,
                                                ObjectMapper objectMapper) {
        return new IdempotencyAspect(storage, properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyExceptionHandler idempotencyExceptionHandler() {
        return new IdempotencyExceptionHandler();
    }
}
