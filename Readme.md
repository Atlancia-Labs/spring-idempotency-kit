# Spring Idempotency Kit

A lightweight, production-ready idempotency solution for Spring Boot 3.x applications.

Prevents duplicate operations in distributed systems — double payments, repeated API calls, message retries, webhook duplication — by ensuring methods execute **exactly once** for a given key.

## Features

- **Annotation-driven** — add `@Idempotent` to any Spring-managed method
- **Dual key resolution** — SpEL expressions or HTTP headers
- **Redis-backed** with distributed locking via `SET NX`
- **Concurrent request handling** — configurable REJECT (409) or WAIT strategy
- **Fail-open design** — Redis outages don't break your application
- **Response caching** — repeated calls return the cached result without re-execution
- **Configurable TTL** — per-method or global defaults
- **Auto-configuration** — zero boilerplate setup with Spring Boot

## Requirements

- Java 21+
- Spring Boot 3.4+
- Redis

## Quick Start

### 1. Add the dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.atlancia:spring-idempotency-kit:0.1.0-SNAPSHOT")
}
```

### 2. Configure Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### 3. Annotate your methods

**SpEL-based key** — extract from method arguments:

```java
@Idempotent(key = "#request.id")
public PaymentResponse processPayment(PaymentRequest request) {
    // executed once per unique request.id
}
```

**Header-based key** — extract from HTTP header:

```java
@Idempotent(headerName = "Idempotency-Key")
public OrderResponse createOrder(OrderRequest request) {
    // executed once per unique Idempotency-Key header value
}
```

## How It Works

1. The idempotency key is resolved (SpEL expression or HTTP header)
2. If a cached result exists for the key — return it immediately
3. If no cache — acquire a distributed lock via Redis `SET NX`
4. Execute the method, serialize the result, store it with TTL
5. Release the lock
6. If the method throws an exception — release the lock, **do not cache** (allows retries)

## Concurrent Request Handling

When a second request arrives while the first is still executing:

| Strategy | Behavior |
|----------|----------|
| `REJECT` (default) | Immediately throws `IdempotencyConflictException` → **409 Conflict** |
| `WAIT` | Polls Redis until the first request completes, then returns the cached result. Throws 409 on timeout. |

```java
@Idempotent(headerName = "Idempotency-Key", onConcurrent = ConcurrentStrategy.WAIT)
public Response slowOperation(Request request) {
    // second concurrent request will wait for this to finish
}
```

## Configuration

All properties are optional with sensible defaults:

```yaml
spring:
  idempotency:
    default-ttl: 24              # TTL value (default: 24)
    default-time-unit: hours     # TTL unit (default: hours)
    default-on-concurrent: reject # REJECT or WAIT (default: reject)
    key-prefix: "idempotency:"   # Redis key prefix (default: "idempotency:")
    lock-timeout: 30s            # Distributed lock TTL (default: 30s)
    wait-timeout: 10s            # Max wait time for WAIT strategy (default: 10s)
    wait-poll-interval: 200ms    # Poll interval for WAIT strategy (default: 200ms)
```

Per-method overrides via annotation:

```java
@Idempotent(key = "#id", ttl = 1, timeUnit = TimeUnit.MINUTES)
public Response shortLivedOperation(String id) { ... }
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Key resolves to `null` | `IdempotencyKeyException` → **400 Bad Request** |
| Missing HTTP header | `IdempotencyKeyException` → **400 Bad Request** |
| Both `key` and `headerName` set (or neither) | `IdempotencyConfigurationException` at invocation |
| Concurrent duplicate (REJECT) | `IdempotencyConflictException` → **409 Conflict** |
| Concurrent duplicate (WAIT, timeout) | `IdempotencyConflictException` → **409 Conflict** |
| Method throws exception | Lock released, result **not cached**, exception propagates |
| Redis unavailable | Fail open — method executes normally, warning logged |

Error responses use RFC 7807 Problem Detail format via `@RestControllerAdvice`.

## Customization

### Custom storage backend

Implement `IdempotencyStorage` and register it as a bean — the auto-configuration will back off:

```java
@Bean
public IdempotencyStorage customStorage() {
    return new MyIdempotencyStorage();
}
```

```java
public interface IdempotencyStorage {
    Optional<IdempotencyResult> get(String key);
    boolean acquireLock(String key, Duration lockTtl);
    void store(String key, IdempotencyResult result, Duration ttl);
    void releaseLock(String key);
}
```

## Architecture

```
@Idempotent annotation
        │
        ▼
 IdempotencyAspect (@Around AOP)
   ├── Key resolution (SpEL / HTTP header)
   ├── Cache lookup
   ├── Distributed lock (SET NX)
   ├── Method execution
   └── Result storage
        │
        ▼
 IdempotencyStorage (interface)
        │
        ▼
 RedisIdempotencyStorage (default impl)
        │
        ▼
 IdempotencyAutoConfiguration (wires everything)
```

## License

TBD
