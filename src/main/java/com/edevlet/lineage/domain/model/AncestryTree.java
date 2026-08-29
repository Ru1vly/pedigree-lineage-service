package com.edevlet.lineage.domain.model;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

public record AncestryTree(
        AncestorPerson rootPerson,
        List<GenerationNode> generations,
        int totalAncestorsFound,
        String verificationSealHash,
        String documentDownloadUrl
) implements Serializable {

    /**
     * Returns a copy carrying this service's real download URL for the task the tree belongs to.
     * The census client cannot supply it - it does not know the transactionId, and it does not
     * host the document - so it returns null and the pipeline stamps the value here once the tree
     * is bound to a task.
     */
    public AncestryTree withDocumentDownloadUrl(String url) {
        return new AncestryTree(rootPerson, generations, totalAncestorsFound, verificationSealHash, url);
    }

    public record AncestorPerson(
            String nationalIdMasked,
            String firstName,
            String lastName,
            String fatherName,
            String motherName,
            LocalDate birthDate,
            String birthPlace,
            String status, // ALIVE, DECEASED
            String relation // SELF, FATHER, MOTHER, PATERNAL_GRANDFATHER, etc.
    ) implements Serializable {}

    public record GenerationNode(
            int generationLevel,
            String relationLabel,
            List<AncestorPerson> members
    ) implements Serializable {}
}
