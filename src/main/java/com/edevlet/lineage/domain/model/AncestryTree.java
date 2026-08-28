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
