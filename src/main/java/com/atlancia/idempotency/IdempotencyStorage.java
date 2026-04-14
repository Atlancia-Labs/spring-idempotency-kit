package com.atlancia.idempotency;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStorage {
    Optional<IdempotencyResult> get(String key);
    boolean acquireLock(String key, Duration lockTtl);
    void store(String key, IdempotencyResult result, Duration ttl);
    void releaseLock(String key);
}
