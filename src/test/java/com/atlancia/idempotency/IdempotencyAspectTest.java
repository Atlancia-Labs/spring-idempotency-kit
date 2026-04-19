package com.atlancia.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyAspectTest {

    @Mock
    private IdempotencyStorage storage;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private IdempotencyAspect aspect;
    private ObjectMapper objectMapper;
    private IdempotencyProperties properties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new IdempotencyProperties();
        aspect = new IdempotencyAspect(storage, properties, objectMapper, null);
        lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
    }

    static class TestService {
        @Idempotent(key = "#id")
        public String processById(String id) {
            return "result";
        }

        @Idempotent(headerName = "Idempotency-Key")
        public String processWithHeader() {
            return "result";
        }

        @Idempotent(key = "#id", ttl = 1, timeUnit = TimeUnit.MINUTES, onConcurrent = ConcurrentStrategy.REJECT)
        public String processWithOverrides(String id) {
            return "result";
        }

        @Idempotent
        public String processNoKey() {
            return "result";
        }

        @Idempotent(key = "#id", headerName = "X-Key")
        public String processBothKeys(String id) {
            return "result";
        }

        @Idempotent(key = "#id", onFailure = FailureStrategy.FAIL_CLOSED)
        public String processFailClosed(String id) {
            return "result";
        }

        @Idempotent(key = "#id", onConcurrent = ConcurrentStrategy.WAIT)
        public String processWithWait(String id) {
            return "result";
        }
    }

    private void setupSpelMethod(String methodName, Object... args) throws Exception {
        Method method = TestService.class.getMethod(methodName, getParamTypes(methodName));
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getTarget()).thenReturn(new TestService());
    }

    private Class<?>[] getParamTypes(String methodName) {
        return switch (methodName) {
            case "processById", "processWithOverrides", "processBothKeys", "processFailClosed", "processWithWait" -> new Class[]{String.class};
            case "processWithHeader", "processNoKey" -> new Class[]{};
            default -> new Class[]{};
        };
    }

    @Test
    void cacheHit_returnsCachedResult() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        var cached = new IdempotencyResult("\"cached-result\"", "java.lang.String");
        when(storage.get("abc-123")).thenReturn(Optional.of(cached));

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("cached-result");
        verify(joinPoint, never()).proceed();
    }

    @Test
    void cacheMiss_lockAcquired_executesAndStores() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn("lock-token-123");
        when(joinPoint.proceed()).thenReturn("fresh-result");

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("fresh-result");
        verify(storage).store(eq("abc-123"), any(IdempotencyResult.class), any(Duration.class));
        verify(storage).releaseLock("abc-123", "lock-token-123");
    }

    @Test
    void cacheMiss_lockNotAcquired_rejectStrategy_throwsConflict() throws Throwable {
        setupSpelMethod("processWithOverrides", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn(null);

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void headerKey_extractsFromRequest() throws Throwable {
        Method method = TestService.class.getMethod("processWithHeader");
        when(methodSignature.getMethod()).thenReturn(method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", "header-key-value");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(storage.get("header-key-value")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("header-key-value"), any(Duration.class))).thenReturn("lock-token-123");
        when(joinPoint.proceed()).thenReturn("header-result");

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("header-result");
        verify(storage).store(eq("header-key-value"), any(IdempotencyResult.class), any(Duration.class));
        verify(storage).releaseLock("header-key-value", "lock-token-123");

        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void missingHeader_throwsKeyException() throws Exception {
        Method method = TestService.class.getMethod("processWithHeader");
        when(methodSignature.getMethod()).thenReturn(method);

        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyKeyException.class);

        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void neitherKeyNorHeader_throwsConfigException() throws Exception {
        Method method = TestService.class.getMethod("processNoKey");
        when(methodSignature.getMethod()).thenReturn(method);

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyConfigurationException.class);
    }

    @Test
    void bothKeyAndHeader_throwsConfigException() throws Exception {
        Method method = TestService.class.getMethod("processBothKeys", String.class);
        when(methodSignature.getMethod()).thenReturn(method);

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyConfigurationException.class);
    }

    @Test
    void methodThrowsException_releasesLock_doesNotCache() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn("lock-token-123");
        when(joinPoint.proceed()).thenThrow(new RuntimeException("business error"));

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("business error");

        verify(storage).releaseLock("abc-123", "lock-token-123");
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void failClosed_cacheReadFailure_throwsStorageException() throws Throwable {
        setupSpelMethod("processFailClosed", "abc-123");
        when(storage.get("abc-123")).thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyStorageException.class);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void failClosed_lockAcquireFailure_throwsStorageException() throws Throwable {
        setupSpelMethod("processFailClosed", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis down"));

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyStorageException.class);
        verify(joinPoint, never()).proceed();
    }

    @Test
    void failOpen_cacheReadFailure_proceedsWithoutIdempotency() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        when(storage.get("abc-123")).thenThrow(new RuntimeException("Redis down"));
        when(joinPoint.proceed()).thenReturn("fallback-result");

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("fallback-result");
    }

    @Test
    void failOpen_lockAcquireFailure_proceedsWithoutIdempotency() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis down"));
        when(joinPoint.proceed()).thenReturn("fallback-result");

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("fallback-result");
    }

    @Test
    void waitStrategy_usesExponentialBackoff() throws Throwable {
        properties.setWaitPollInitialInterval(Duration.ofMillis(50));
        properties.setWaitPollMaxInterval(Duration.ofMillis(200));
        properties.setWaitTimeout(Duration.ofMillis(500));

        setupSpelMethod("processWithWait", "abc-123");
        when(storage.get("abc-123"))
                .thenReturn(Optional.empty())  // initial cache check
                .thenReturn(Optional.empty())  // poll 1 (after 50ms)
                .thenReturn(Optional.empty())  // poll 2 (after 100ms)
                .thenReturn(Optional.of(new IdempotencyResult("\"waited-result\"", "java.lang.String"))); // poll 3 (after 200ms)
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn(null);

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("waited-result");
        verify(storage, times(4)).get("abc-123"); // 1 initial + 3 polls
    }

    @Test
    void waitStrategy_timeout_throwsConflict() throws Throwable {
        properties.setWaitPollInitialInterval(Duration.ofMillis(50));
        properties.setWaitPollMaxInterval(Duration.ofMillis(50));
        properties.setWaitTimeout(Duration.ofMillis(120));

        setupSpelMethod("processWithWait", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn(null);

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Timeout");
    }

    @Test
    void waitStrategy_interrupted_throwsConflictAndRestoresFlag() throws Throwable {
        properties.setWaitPollInitialInterval(Duration.ofMillis(50));
        properties.setWaitPollMaxInterval(Duration.ofMillis(50));
        properties.setWaitTimeout(Duration.ofSeconds(5));

        setupSpelMethod("processWithWait", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn(null);

        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("Interrupted");

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void lockReleaseFailure_isSwallowd_doesNotAffectResult() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn("lock-token-123");
        when(joinPoint.proceed()).thenReturn("success");
        doThrow(new RuntimeException("Redis gone")).when(storage).releaseLock("abc-123", "lock-token-123");

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("success");
        verify(storage).store(eq("abc-123"), any(IdempotencyResult.class), any(Duration.class));
    }

    @Test
    void storeFailure_afterExecution_propagatesException() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn("lock-token-123");
        when(joinPoint.proceed()).thenReturn("result");
        doThrow(new RuntimeException("Redis write failed")).when(storage)
                .store(eq("abc-123"), any(IdempotencyResult.class), any(Duration.class));

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Redis write failed");

        verify(storage).releaseLock("abc-123", "lock-token-123");
    }

    @Test
    void cacheHit_deserializationFailure_failOpen_proceedsWithoutIdempotency() throws Throwable {
        setupSpelMethod("processById", "abc-123");
        var cached = new IdempotencyResult("not-valid-json!!!", "java.lang.String");
        when(storage.get("abc-123")).thenReturn(Optional.of(cached));
        when(joinPoint.proceed()).thenReturn("fresh-result");

        Object result = aspect.handleIdempotent(joinPoint);

        assertThat(result).isEqualTo("fresh-result");
    }

    @Test
    void cacheHit_deserializationFailure_failClosed_throwsStorageException() throws Throwable {
        setupSpelMethod("processFailClosed", "abc-123");
        var cached = new IdempotencyResult("not-valid-json!!!", "java.lang.String");
        when(storage.get("abc-123")).thenReturn(Optional.of(cached));

        assertThatThrownBy(() -> aspect.handleIdempotent(joinPoint))
                .isInstanceOf(IdempotencyStorageException.class);
    }

    @Test
    void annotationTtl_overridesDefaultTtl() throws Throwable {
        setupSpelMethod("processWithOverrides", "abc-123");
        when(storage.get("abc-123")).thenReturn(Optional.empty());
        when(storage.acquireLock(eq("abc-123"), any(Duration.class))).thenReturn("lock-token-123");
        when(joinPoint.proceed()).thenReturn("result");

        aspect.handleIdempotent(joinPoint);

        verify(storage).store(eq("abc-123"), any(IdempotencyResult.class), eq(Duration.ofMinutes(1)));
    }
}
