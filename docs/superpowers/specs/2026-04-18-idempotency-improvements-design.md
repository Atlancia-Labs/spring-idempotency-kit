# Spring Idempotency Kit — Improvements Design

Driven by real-world feedback on handling thousands of concurrent requests and fail-open behavior. Two-phase delivery: correctness first, then observability.

## Phase 1: Correctness

### 1.1 Owner-Based Lock Safety

**Problem:** `releaseLock` does a plain `DELETE` — any request can release another's lock. Under TTL expiry with concurrent requests, this causes duplicate executions.

**Fix:**

- `acquireLock` generates a UUID, stores it as the lock value via `SET NX`
- `releaseLock` uses a Lua script that compares the value before deleting:

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

**Interface change** — `IdempotencyStorage` SPI breaks:

```java
String acquireLock(String key, Duration lockTtl);  // returns token or null
void releaseLock(String key, String lockToken);
```

`acquireLock` returns a UUID string on success, `null` on failure. `releaseLock` takes the token and only deletes if it matches.

`IdempotencyAspect` passes the token through from acquisition to release.

### 1.2 Configurable Fail-Open vs Fail-Closed

**Problem:** Fail-open is hardcoded. For payment-critical operations, users may prefer to fail the request when Redis is unavailable rather than risk duplicates.

**New enum:**

```java
public enum FailureStrategy {
    DEFAULT,     // resolve from global config
    FAIL_OPEN,   // proceed without idempotency (current behavior)
    FAIL_CLOSED  // throw exception, reject the request
}
```

**Annotation change:**

```java
@Idempotent(key = "#req.id", onFailure = FailureStrategy.FAIL_CLOSED)
```

**Properties change:**

```yaml
spring.idempotency.default-on-failure: fail-open  # default, preserves current behavior
```

**Aspect behavior** — the three existing try-catch blocks (cache lookup, lock acquisition, lock release) check the resolved failure strategy:

- `FAIL_OPEN`: log warning, proceed without idempotency (current behavior)
- `FAIL_CLOSED`: throw `IdempotencyStorageException` → mapped to **503 Service Unavailable** by the exception handler

**Special case:** Lock release failure always logs a warning regardless of strategy — the method already executed, and the lock will expire via TTL. Throwing here would mask a successful result.

**New exception:** `IdempotencyStorageException extends RuntimeException`

**Exception handler addition:**

```java
@ExceptionHandler(IdempotencyStorageException.class)
public ProblemDetail handleStorageException(IdempotencyStorageException ex) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
}
```

## Phase 2: Observability & Ergonomics

### 2.1 Micrometer Metrics

New `IdempotencyMetrics` class wrapping `MeterRegistry`:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `idempotency.cache.hit` | Counter | `key_prefix` | Cached result returned |
| `idempotency.cache.miss` | Counter | `key_prefix` | No cached result, proceeding to execute |
| `idempotency.lock.acquired` | Counter | `key_prefix` | Lock successfully acquired |
| `idempotency.lock.rejected` | Counter | `key_prefix` | Lock not acquired (concurrent dup) |
| `idempotency.failopen` | Counter | `key_prefix`, `phase` (cache/lock) | Fail-open triggered |
| `idempotency.conflict` | Counter | `key_prefix`, `strategy` (reject/wait_timeout) | 409 returned |
| `idempotency.execution` | Timer | `key_prefix` | Duration of actual method execution |

**Auto-configuration:** Created `@ConditionalOnClass(MeterRegistry.class)`, injected into the aspect as `Optional<IdempotencyMetrics>`. If Micrometer isn't on the classpath — no metrics, zero overhead.

### 2.2 Structured Logging

Enhance existing log statements with key-value context:

- `idempotency.key` — the resolved idempotency key
- `idempotency.strategy` — concurrent strategy in use
- `idempotency.outcome` — one of: `hit`, `miss`, `failopen`, `conflict`, `executed`

Uses SLF4J structured arguments. No new dependencies.

### 2.3 Exponential Backoff for WAIT Strategy

**Current:** Fixed 200ms polling with `Thread.sleep`.

**New:** Start at `waitPollInitialInterval` (default 100ms), double each iteration, cap at `waitPollMaxInterval` (default 1s). Same `waitTimeout` deadline.

```
100ms → 200ms → 400ms → 800ms → 1000ms → 1000ms → ...
```

**Properties change:**

```yaml
spring.idempotency.wait-poll-initial-interval: 100ms  # new
spring.idempotency.wait-poll-max-interval: 1s          # new
```

Replaces the existing `wait-poll-interval` property.

## Delivery

- **Phase 1** ships first — fixes correctness bugs, independently valuable
- **Phase 2** layers on top — additive, no correctness dependencies on Phase 1
- Each phase gets its own branch/PR
