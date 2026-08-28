package com.edevlet.lineage.domain.model;

public enum ProcessingPhase {
    INITIATED("Request Received and Queued"),
    ANCESTRY_TRAVERSAL("Traversing Legacy Census & Family Registry Graph"),
    IDENTITY_VERIFICATION("Verifying Civil Status, Birth & Death Records"),
    DOCUMENT_GENERATION("Generating Certified Pedigree Document"),
    FINISHED("Lineage Processing Finished");

    private final String description;

    ProcessingPhase(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
