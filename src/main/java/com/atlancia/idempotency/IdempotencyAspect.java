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
    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public IdempotencyAspect(IdempotencyStorage storage,
                             IdempotencyProperties properties,
                             ObjectMapper objectMapper) {
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(com.atlancia.idempotency.Idempotent)")
    public Object handleIdempotent(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent annotation = method.getAnnotation(Idempotent.class);

        String key = resolveKey(annotation, joinPoint, signature);
        Duration ttl = properties.getResolvedTtl(annotation.ttl(), annotation.timeUnit());
        ConcurrentStrategy strategy = properties.getResolvedConcurrentStrategy(annotation.onConcurrent());

        // Check cache
        try {
            Optional<IdempotencyResult> cached = storage.get(key);
            if (cached.isPresent()) {
                log.debug("Idempotency cache hit for key: {}", key);
                return deserialize(cached.get(), method.getReturnType());
            }
        } catch (Exception e) {
            log.warn("Failed to read from idempotency storage, proceeding without idempotency", e);
            return joinPoint.proceed();
        }

        // Try to acquire lock
        boolean lockAcquired;
        try {
            lockAcquired = storage.acquireLock(key, properties.getLockTimeout());
        } catch (Exception e) {
            log.warn("Failed to acquire idempotency lock, proceeding without idempotency", e);
            return joinPoint.proceed();
        }

        if (!lockAcquired) {
            return handleConcurrent(key, strategy, method.getReturnType());
        }

        // Execute and store
        try {
            Object result = joinPoint.proceed();
            String serialized = objectMapper.writeValueAsString(result);
            var idempotencyResult = new IdempotencyResult(serialized, method.getReturnType().getName());
            storage.store(key, idempotencyResult, ttl);
            return result;
        } catch (Exception e) {
            throw e;
        } finally {
            try {
                storage.releaseLock(key);
            } catch (Exception ex) {
                log.warn("Failed to release idempotency lock for key: {}", key, ex);
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
            throw new IdempotencyConflictException("Concurrent duplicate request for key: " + key);
        }

        // WAIT strategy: poll until result appears
        long deadline = System.currentTimeMillis() + properties.getWaitTimeout().toMillis();
        long pollInterval = properties.getWaitPollInterval().toMillis();

        while (System.currentTimeMillis() < deadline) {
            Optional<IdempotencyResult> cached = storage.get(key);
            if (cached.isPresent()) {
                return deserialize(cached.get(), returnType);
            }
            try {
                Thread.sleep(pollInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdempotencyConflictException("Interrupted while waiting for idempotent result: " + key);
            }
        }
        throw new IdempotencyConflictException("Timeout waiting for idempotent result for key: " + key);
    }

    private Object deserialize(IdempotencyResult result, Class<?> returnType) {
        try {
            return objectMapper.readValue(result.body(), returnType);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached idempotency result", e);
        }
    }
}
