package com.atlancia.idempotency;

public record IdempotencyResult(String body, String typeName, String requestHash) {

    public IdempotencyResult(String body, String typeName) {
        this(body, typeName, null);
    }
}
