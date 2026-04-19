package com.atlancia.idempotency;

public class IdempotencyStorageException extends RuntimeException {
    public IdempotencyStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
