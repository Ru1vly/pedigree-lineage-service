package com.edevlet.lineage.domain.exception;

public class PipelineProcessingException extends RuntimeException {
    private final String errorCode;

    public PipelineProcessingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public PipelineProcessingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
