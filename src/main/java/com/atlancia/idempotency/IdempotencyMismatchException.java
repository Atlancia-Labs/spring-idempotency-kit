package com.atlancia.idempotency;

public class IdempotencyMismatchException extends RuntimeException {
    public IdempotencyMismatchException(String message) {
        super(message);
    }
}
