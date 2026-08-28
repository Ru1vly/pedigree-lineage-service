package com.edevlet.lineage.domain.exception;

public class UnauthorizedTaskAccessException extends RuntimeException {
    public UnauthorizedTaskAccessException(String userId, String transactionId) {
        super("User '" + userId + "' is not authorized to access transaction ID: " + transactionId);
    }
}
