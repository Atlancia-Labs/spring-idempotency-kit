package com.atlancia.idempotency;

public enum FailureStrategy {
    DEFAULT,
    FAIL_OPEN,
    FAIL_CLOSED
}
