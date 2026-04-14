package com.atlancia.idempotency;

public class IdempotencyConfigurationException extends RuntimeException {
    public IdempotencyConfigurationException(String message) {
        super(message);
    }
}
