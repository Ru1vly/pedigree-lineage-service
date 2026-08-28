package com.edevlet.lineage.domain.exception;

public class DuplicateRequestException extends RuntimeException {
    private final String existingTransactionId;

    public DuplicateRequestException(String idempotencyKey, String existingTransactionId) {
        super("Duplicate query request detected with idempotency key: " + idempotencyKey);
        this.existingTransactionId = existingTransactionId;
    }

    public String getExistingTransactionId() {
        return existingTransactionId;
    }
}
