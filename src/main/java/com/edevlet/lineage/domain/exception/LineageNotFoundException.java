package com.edevlet.lineage.domain.exception;

public class LineageNotFoundException extends RuntimeException {
    public LineageNotFoundException(String transactionId) {
        super("Lineage query task not found with transaction ID: " + transactionId);
    }
}
