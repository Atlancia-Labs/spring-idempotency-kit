package com.atlancia.idempotency;

public enum ConcurrentStrategy {
    DEFAULT,
    REJECT,
    WAIT
}
