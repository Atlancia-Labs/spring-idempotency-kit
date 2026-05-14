package com.atlancia.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import org.springframework.data.redis.core.ScanOptions;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class RedisIdempotencyStorage implements IdempotencyStorage {

    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;

    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "  return redis.call('del', KEYS[1]) " +
                "else " +
                "  return 0 " +
                "end"
        );
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

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
    public String acquireLock(String key, Duration lockTtl) {
        String token = UUID.randomUUID().toString();
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(lockKey(key), token, lockTtl);
        return Boolean.TRUE.equals(result) ? token : null;
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
    public void releaseLock(String key, String lockToken) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey(key)), lockToken);
    }

    @Override
    public long keyCount() {
        long count = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .match(keyPrefix + "*:result")
                .count(1000)
                .build();
        try (var cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    private String resultKey(String key) {
        return keyPrefix + key + ":result";
    }

    private String lockKey(String key) {
        return keyPrefix + key + ":lock";
    }
}
