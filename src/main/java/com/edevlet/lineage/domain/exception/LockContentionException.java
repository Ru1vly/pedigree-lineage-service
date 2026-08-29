package com.edevlet.lineage.domain.exception;

/**
 * Raised when another worker already holds the processing lock for a task.
 *
 * <p>This is explicitly NOT a pipeline failure. It must never reach PipelineFailureHandler - the
 * attempt did not fail, it never started, so incrementing the retry count or recording a cause
 * against the task would be wrong.
 *
 * <p>It exists because the orchestrator used to <em>return normally</em> on lock contention. The
 * listener read that as success and committed the offset, so the record was gone: if the lock holder
 * then died, or was processing a different delivery of the same task, nothing reprocessed it and the
 * task sat at PROCESSING until someone noticed. NOTES.md section 5 presents the lock as what makes
 * at-least-once delivery safe, and on that path it was what made delivery lossy.
 *
 * <p>Throwing instead leaves the offset uncommitted so the broker redelivers. KafkaConfig classifies
 * this type onto a bounded backoff rather than the immediate dead-letter every other exception gets;
 * contention means "come back shortly", not "this record is poison".
 */
public class LockContentionException extends RuntimeException {

    private final String transactionId;

    public LockContentionException(String transactionId) {
        super("Lineage task " + transactionId + " is already held by another worker; redelivery required");
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }
}
