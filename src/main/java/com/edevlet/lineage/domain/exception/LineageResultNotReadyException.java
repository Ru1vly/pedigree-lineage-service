package com.edevlet.lineage.domain.exception;

public class LineageResultNotReadyException extends RuntimeException {
    public LineageResultNotReadyException(String transactionId) {
        super("Lineage query task " + transactionId + " has not completed processing yet; no document is available.");
    }
}
