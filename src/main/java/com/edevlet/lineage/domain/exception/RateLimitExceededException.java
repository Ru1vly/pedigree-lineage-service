package com.edevlet.lineage.domain.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String key) {
        super("Rate limit exceeded for client: " + key + ". Please try again later.");
    }
}
