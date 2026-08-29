package com.edevlet.lineage.domain.exception;

public class DuplicateRequestException extends RuntimeException {

    /**
     * The transactionId of the request that won, when it is known. Null on the concurrent-insert
     * path in LineageQueryService: the unique-constraint violation poisons that transaction, so the
     * winning row cannot be read back before rollback. Retrying the submit resolves to it.
     */
    private final String existingTransactionId;

    public DuplicateRequestException(String idempotencyKey, String existingTransactionId) {
        super("Duplicate query request detected with idempotency key: " + idempotencyKey
                + ". A concurrent request with the same key is already registered; retry to retrieve it.");
        this.existingTransactionId = existingTransactionId;
    }

    public String getExistingTransactionId() {
        return existingTransactionId;
    }
}
