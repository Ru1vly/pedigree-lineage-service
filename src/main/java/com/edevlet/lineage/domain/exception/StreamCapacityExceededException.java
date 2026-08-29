package com.edevlet.lineage.domain.exception;

/**
 * This instance is already serving as many live progress streams as it is configured to serve.
 *
 * <p>Maps to 503 with a {@code Retry-After}, not 429: the caller has done nothing wrong and is not
 * being throttled - the instance is at capacity, and the ordinary status endpoint remains available
 * as the fallback.
 */
public class StreamCapacityExceededException extends RuntimeException {
    public StreamCapacityExceededException(int maxConcurrentStreams) {
        super(("This instance is already serving its maximum of %d concurrent progress streams. "
                + "Poll GET /api/v1/lineage/queries/{transactionId} instead, or retry shortly.")
                .formatted(maxConcurrentStreams));
    }
}
