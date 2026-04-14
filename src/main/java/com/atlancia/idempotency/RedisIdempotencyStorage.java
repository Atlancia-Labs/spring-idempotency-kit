package com.atlancia.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

public class RedisIdempotencyStorage implements IdempotencyStorage {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisIdempotencyStorage(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<IdempotencyResult> get(String key) {
        String json = redisTemplate.opsForValue().get(resultKey(key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, IdempotencyResult.class));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize idempotency result", e);
        }
    }

    @Override
    public boolean acquireLock(String key, Duration lockTtl) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(lockKey(key), "locked", lockTtl);
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void store(String key, IdempotencyResult result, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue().set(resultKey(key), json, ttl);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize idempotency result", e);
        }
    }

    @Override
    public void releaseLock(String key) {
        redisTemplate.delete(lockKey(key));
    }

    private String resultKey(String key) {
        return keyPrefix + key + ":result";
    }

    private String lockKey(String key) {
        return keyPrefix + key + ":lock";
    }
}
