package com.atlancia.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;

@Aspect
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);

    private final IdempotencyStorage storage;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final IdempotencyMetrics metrics;
    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public IdempotencyAspect(IdempotencyStorage storage,
                             IdempotencyProperties properties,
                             ObjectMapper objectMapper,
                             IdempotencyMetrics metrics) {
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Around("@annotation(com.atlancia.idempotency.Idempotent)")
    public Object handleIdempotent(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent annotation = method.getAnnotation(Idempotent.class);

        String key = resolveKey(annotation, joinPoint, signature);
        Duration ttl = properties.getResolvedTtl(annotation.ttl(), annotation.timeUnit());
        ConcurrentStrategy strategy = properties.getResolvedConcurrentStrategy(annotation.onConcurrent());
        FailureStrategy failureStrategy = properties.getResolvedFailureStrategy(annotation.onFailure());

        // Check cache
        try {
            Optional<IdempotencyResult> cached = storage.get(key);
            if (cached.isPresent()) {
                log.debug("Idempotency cache hit for key={}, outcome=hit", key);
                if (metrics != null) metrics.recordCacheHit();
                return deserialize(cached.get(), method.getReturnType());
            }
        } catch (Exception e) {
            if (metrics != null) metrics.recordStorageError("cache");
            if (failureStrategy == FailureStrategy.FAIL_CLOSED) {
                throw new IdempotencyStorageException("Failed to read from idempotency storage", e);
            }
            log.warn("Idempotency storage read failed for key={}, outcome=failopen, phase=cache", key, e);
            if (metrics != null) metrics.recordFailOpen("cache");
            return joinPoint.proceed();
        }

        // Try to acquire lock
        String lockToken;
        try {
            lockToken = storage.acquireLock(key, properties.getLockTimeout());
        } catch (Exception e) {
            if (metrics != null) metrics.recordStorageError("lock");
            if (failureStrategy == FailureStrategy.FAIL_CLOSED) {
                throw new IdempotencyStorageException("Failed to acquire idempotency lock", e);
            }
            log.warn("Idempotency lock acquisition failed for key={}, outcome=failopen, phase=lock", key, e);
            if (metrics != null) metrics.recordFailOpen("lock");
            return joinPoint.proceed();
        }

        if (lockToken == null) {
            if (metrics != null) metrics.recordLockRejected();
            return handleConcurrent(key, strategy, method.getReturnType());
        }

        if (metrics != null) {
            metrics.recordCacheMiss();
            metrics.recordLockAcquired();
        }

        // Execute and store
        try {
            Object result;
            if (metrics != null) {
                try {
                    result = metrics.recordExecution(() -> {
                        try {
                            return joinPoint.proceed();
                        } catch (Exception e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                    });
                } catch (Exception e) {
                    metrics.recordExecutionError(e.getClass().getSimpleName());
                    throw e;
                }
            } else {
                result = joinPoint.proceed();
            }
            String serialized;
            try {
                serialized = objectMapper.writeValueAsString(result);
            } catch (Exception e) {
                if (metrics != null) metrics.recordSerializationError();
                throw new RuntimeException("Failed to serialize idempotency result", e);
            }
            var idempotencyResult = new IdempotencyResult(serialized, method.getReturnType().getName());
            try {
                storage.store(key, idempotencyResult, ttl);
            } catch (Exception e) {
                if (metrics != null) metrics.recordStorageError("store");
                throw e;
            }
            log.debug("Idempotency result stored for key={}, outcome=executed", key);
            return result;
        } finally {
            try {
                storage.releaseLock(key, lockToken);
            } catch (Exception ex) {
                log.warn("Failed to release idempotency lock for key={}", key, ex);
                if (metrics != null) metrics.recordLockReleaseFailure();
            }
        }
    }

    private String resolveKey(Idempotent annotation, ProceedingJoinPoint joinPoint, MethodSignature signature) {
        boolean hasKey = !annotation.key().isEmpty();
        boolean hasHeader = !annotation.headerName().isEmpty();

        if (hasKey == hasHeader) {
            throw new IdempotencyConfigurationException(
                    "Exactly one of 'key' or 'headerName' must be specified on @Idempotent");
        }

        if (hasHeader) {
            return resolveHeaderKey(annotation.headerName());
        }
        return resolveSpelKey(annotation.key(), joinPoint, signature);
    }

    private String resolveHeaderKey(String headerName) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IdempotencyKeyException("No HTTP request context available for header-based key resolution");
        }
        HttpServletRequest request = attrs.getRequest();
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            throw new IdempotencyKeyException("Missing required idempotency header: " + headerName);
        }
        return value;
    }

    private String resolveSpelKey(String expression, ProceedingJoinPoint joinPoint, MethodSignature signature) {
        var context = new MethodBasedEvaluationContext(
                joinPoint.getTarget(),
                signature.getMethod(),
                joinPoint.getArgs(),
                parameterNameDiscoverer
        );

        Object value = spelParser.parseExpression(expression).getValue(context);
        if (value == null) {
            throw new IdempotencyKeyException("Idempotency key resolved to null for expression: " + expression);
        }
        return value.toString();
    }

    private Object handleConcurrent(String key, ConcurrentStrategy strategy, Class<?> returnType) {
        if (strategy == ConcurrentStrategy.REJECT) {
            log.debug("Idempotency concurrent reject for key={}, outcome=conflict, strategy=reject", key);
            if (metrics != null) metrics.recordConflict("reject");
            throw new IdempotencyConflictException("Concurrent duplicate request for key: " + key);
        }

        // WAIT strategy: poll with exponential backoff
        Object waitSample = metrics != null ? metrics.startWaitTimer() : null;
        long deadline = System.currentTimeMillis() + properties.getWaitTimeout().toMillis();
        long currentInterval = properties.getWaitPollInitialInterval().toMillis();
        long maxInterval = properties.getWaitPollMaxInterval().toMillis();

        try {
            while (System.currentTimeMillis() < deadline) {
                Optional<IdempotencyResult> cached = storage.get(key);
                if (cached.isPresent()) {
                    log.debug("Idempotency wait resolved for key={}, outcome=hit", key);
                    if (metrics != null) metrics.recordCacheHit();
                    return deserialize(cached.get(), returnType);
                }
                try {
                    Thread.sleep(currentInterval);
                    currentInterval = Math.min(currentInterval * 2, maxInterval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (metrics != null) metrics.recordConflict("wait_interrupted");
                    throw new IdempotencyConflictException("Interrupted while waiting for idempotent result: " + key);
                }
            }
            log.debug("Idempotency wait timeout for key={}, outcome=conflict, strategy=wait_timeout", key);
            if (metrics != null) metrics.recordConflict("wait_timeout");
            throw new IdempotencyConflictException("Timeout waiting for idempotent result for key: " + key);
        } finally {
            if (waitSample != null) metrics.stopWaitTimer(waitSample);
        }
    }

    private Object deserialize(IdempotencyResult result, Class<?> returnType) {
        try {
            return objectMapper.readValue(result.body(), returnType);
        } catch (Exception e) {
            if (metrics != null) metrics.recordSerializationError();
            throw new RuntimeException("Failed to deserialize cached idempotency result", e);
        }
    }
}
