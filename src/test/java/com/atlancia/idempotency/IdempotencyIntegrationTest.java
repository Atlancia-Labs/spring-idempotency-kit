package com.atlancia.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyIntegrationTest {

    @Configuration
    @EnableAutoConfiguration
    @EnableAspectJAutoProxy
    static class TestConfig {

        @Bean
        public TestController testController() {
            return new TestController();
        }

        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @RestController
    static class TestController {

        private final AtomicInteger callCount = new AtomicInteger(0);

        @PostMapping("/test/spel")
        @Idempotent(key = "#id")
        public Map<String, Object> spelEndpoint(@RequestHeader("X-Request-Id") String id) {
            return Map.of("count", callCount.incrementAndGet(), "id", id);
        }

        @PostMapping("/test/header")
        @Idempotent(headerName = "Idempotency-Key")
        public Map<String, Object> headerEndpoint(@RequestBody Map<String, String> body) {
            return Map.of("count", callCount.incrementAndGet(), "data", body.getOrDefault("data", ""));
        }

        @PostMapping("/test/reject")
        @Idempotent(key = "#id", onConcurrent = ConcurrentStrategy.REJECT)
        public Map<String, Object> rejectEndpoint(@RequestHeader("X-Request-Id") String id) throws InterruptedException {
            Thread.sleep(500);
            return Map.of("count", callCount.incrementAndGet());
        }

        @PostMapping("/test/error")
        @Idempotent(key = "#id")
        public Map<String, Object> errorEndpoint(@RequestHeader("X-Request-Id") String id) {
            callCount.incrementAndGet();
            throw new RuntimeException("business error");
        }

        @PostMapping("/test/fail-closed")
        @Idempotent(key = "#id", onFailure = FailureStrategy.FAIL_CLOSED)
        public Map<String, Object> failClosedEndpoint(@RequestHeader("X-Request-Id") String id) {
            return Map.of("count", callCount.incrementAndGet(), "id", id);
        }

        @PostMapping("/test/storage-error")
        public Map<String, Object> storageErrorEndpoint() {
            throw new IdempotencyStorageException("Redis unavailable", new RuntimeException("connection refused"));
        }

        @PostMapping("/test/wait")
        @Idempotent(key = "#id", onConcurrent = ConcurrentStrategy.WAIT)
        public Map<String, Object> waitEndpoint(@RequestHeader("X-Request-Id") String id) throws InterruptedException {
            Thread.sleep(300);
            return Map.of("count", callCount.incrementAndGet());
        }

        public int getCallCount() {
            return callCount.get();
        }

        public void resetCount() {
            callCount.set(0);
        }
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
    private MockMvc mockMvc;

    @Autowired
    private TestController testController;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void spelKey_firstCallExecutes_secondReturnsCached() throws Exception {
        testController.resetCount();

        mockMvc.perform(post("/test/spel")
                        .header("X-Request-Id", "spel-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(post("/test/spel")
                        .header("X-Request-Id", "spel-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(testController.getCallCount()).isEqualTo(1);
    }

    @Test
    void headerKey_firstCallExecutes_secondReturnsCached() throws Exception {
        testController.resetCount();

        mockMvc.perform(post("/test/header")
                        .header("Idempotency-Key", "header-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(post("/test/header")
                        .header("Idempotency-Key", "header-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(testController.getCallCount()).isEqualTo(1);
    }

    @Test
    void missingHeader_returns400() throws Exception {
        mockMvc.perform(post("/test/header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":\"hello\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void differentKeys_executeSeparately() throws Exception {
        testController.resetCount();

        mockMvc.perform(post("/test/spel")
                        .header("X-Request-Id", "diff-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(post("/test/spel")
                        .header("X-Request-Id", "diff-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        assertThat(testController.getCallCount()).isEqualTo(2);
    }

    @Test
    void concurrentReject_returns409() throws Exception {
        testController.resetCount();

        var thread = new Thread(() -> {
            try {
                mockMvc.perform(post("/test/reject")
                        .header("X-Request-Id", "concurrent-1"));
            } catch (Exception ignored) {
            }
        });
        thread.start();

        Thread.sleep(100);

        mockMvc.perform(post("/test/reject")
                        .header("X-Request-Id", "concurrent-1"))
                .andExpect(status().isConflict());

        thread.join();
    }

    @Test
    void failClosed_withWorkingRedis_behavesNormally() throws Exception {
        testController.resetCount();

        mockMvc.perform(post("/test/fail-closed")
                        .header("X-Request-Id", "fc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(post("/test/fail-closed")
                        .header("X-Request-Id", "fc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        assertThat(testController.getCallCount()).isEqualTo(1);
    }

    @Test
    void errorDoesNotCache_allowsRetry() {
        testController.resetCount();

        assertThatThrownBy(() -> mockMvc.perform(post("/test/error")
                        .header("X-Request-Id", "error-1")))
                .hasCauseInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> mockMvc.perform(post("/test/error")
                        .header("X-Request-Id", "error-1")))
                .hasCauseInstanceOf(RuntimeException.class);

        assertThat(testController.getCallCount()).isEqualTo(2);
    }

    @Test
    void metrics_recordCacheHitAndMiss() throws Exception {
        testController.resetCount();

        mockMvc.perform(post("/test/spel")
                        .header("X-Request-Id", "metrics-1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/test/spel")
                        .header("X-Request-Id", "metrics-1"))
                .andExpect(status().isOk());

        assertThat(meterRegistry.counter("idempotency.cache.miss", "key_prefix", "idempotency:").count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.counter("idempotency.cache.hit", "key_prefix", "idempotency:").count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void storageException_returns503() throws Exception {
        mockMvc.perform(post("/test/storage-error"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void concurrentWait_secondRequestWaitsAndGetsCachedResult() throws Exception {
        testController.resetCount();

        var thread = new Thread(() -> {
            try {
                mockMvc.perform(post("/test/wait")
                                .header("X-Request-Id", "wait-1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.count").value(1));
            } catch (Exception ignored) {
            }
        });
        thread.start();

        Thread.sleep(100); // let first request acquire lock

        mockMvc.perform(post("/test/wait")
                        .header("X-Request-Id", "wait-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1)); // same cached result

        thread.join();
        assertThat(testController.getCallCount()).isEqualTo(1);
    }
}
