package com.atlancia.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisIdempotencyStorageTest {

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    private RedisIdempotencyStorage storage;

    @BeforeEach
    void setUp() {
        storage = new RedisIdempotencyStorage(redisTemplate, new ObjectMapper(), "idempotency:");
        var keys = redisTemplate.keys("idempotency:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void get_returnsEmpty_whenKeyDoesNotExist() {
        Optional<IdempotencyResult> result = storage.get("nonexistent");
        assertThat(result).isEmpty();
    }

    @Test
    void storeAndGet_roundTrips() {
        var idempotencyResult = new IdempotencyResult("{\"amount\":100}", "com.example.PaymentResponse");
        storage.store("payment-123", idempotencyResult, Duration.ofMinutes(5));

        Optional<IdempotencyResult> result = storage.get("payment-123");
        assertThat(result).isPresent();
        assertThat(result.get().body()).isEqualTo("{\"amount\":100}");
        assertThat(result.get().typeName()).isEqualTo("com.example.PaymentResponse");
    }

    @Test
    void acquireLock_succeedsOnFirstCall() {
        boolean acquired = storage.acquireLock("lock-key", Duration.ofSeconds(10));
        assertThat(acquired).isTrue();
    }

    @Test
    void acquireLock_failsOnSecondCall() {
        storage.acquireLock("lock-key", Duration.ofSeconds(10));
        boolean second = storage.acquireLock("lock-key", Duration.ofSeconds(10));
        assertThat(second).isFalse();
    }

    @Test
    void releaseLock_allowsReacquire() {
        storage.acquireLock("lock-key", Duration.ofSeconds(10));
        storage.releaseLock("lock-key");
        boolean reacquired = storage.acquireLock("lock-key", Duration.ofSeconds(10));
        assertThat(reacquired).isTrue();
    }

    @Test
    void store_respectsTtl() throws InterruptedException {
        var result = new IdempotencyResult("{}", "java.lang.Object");
        storage.store("ttl-key", result, Duration.ofSeconds(1));

        assertThat(storage.get("ttl-key")).isPresent();
        Thread.sleep(1500);
        assertThat(storage.get("ttl-key")).isEmpty();
    }
}
