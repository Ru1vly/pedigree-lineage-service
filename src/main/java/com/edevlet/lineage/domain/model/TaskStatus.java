package com.edevlet.lineage.domain.model;

public enum TaskStatus {
    SUBMITTED,
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED;

    /**
     * True once the task has reached a status it can never leave. Callers that poll - the SSE
     * progress stream in particular - use this to decide when to stop rather than comparing
     * status name strings at each site.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
