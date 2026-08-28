package com.edevlet.lineage.infrastructure.client;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.AncestryTree.AncestorPerson;
import com.edevlet.lineage.domain.model.AncestryTree.GenerationNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LegacyCensusGraphClientImpl implements LegacyCensusGraphClient {

    @Override
    @CircuitBreaker(name = "legacyCensusBackend", fallbackMethod = "fallbackAncestryTraversal")
    @Retry(name = "legacyCensusBackend")
    public AncestryTree traverseAncestryGraph(String nationalId, int depth) {
        log.info("Querying legacy census graph database for nationalId={}, depth={}", maskNationalId(nationalId), depth);

        // Simulate rare fault for testing circuit breaker if triggered
        if ("99999999999".equals(nationalId)) {
            log.error("Legacy census graph database connection timed out for nationalId={}", nationalId);
            throw new RuntimeException("Legacy census database connection timeout");
        }

        AncestorPerson root = new AncestorPerson(
                maskNationalId(nationalId),
                "AHMET",
                "YILMAZ",
                "MEHMET",
                "FATMA",
                LocalDate.of(1985, 4, 12),
                "ANKARA",
                "SAĞ",
                "KENDİSİ"
        );

        List<GenerationNode> generations = new ArrayList<>();

        if (depth >= 1) {
            generations.add(new GenerationNode(1, "Ana / Baba (1. Kuşak)", List.of(
                    new AncestorPerson("382*****102", "MEHMET", "YILMAZ", "MUSTAFA", "AYŞE", LocalDate.of(1958, 8, 20), "KONYA", "SAĞ", "BABA"),
                    new AncestorPerson("192*****842", "FATMA", "YILMAZ", "ALİ", "EMİNE", LocalDate.of(1962, 11, 5), "KAYSERİ", "SAĞ", "ANNE")
            )));
        }

        if (depth >= 2) {
            generations.add(new GenerationNode(2, "Büyükbaba / Büyükanne (2. Kuşak)", List.of(
                    new AncestorPerson("102*****771", "MUSTAFA", "YILMAZ", "ALİ", "ZEYNEP", LocalDate.of(1930, 2, 14), "KONYA", "VEFAT", "DEDE"),
                    new AncestorPerson("102*****772", "AYŞE", "YILMAZ", "HÜSEYİN", "FATMA", LocalDate.of(1933, 6, 18), "KONYA", "VEFAT", "NENE")
            )));
        }

        return new AncestryTree(root, generations, 5, "SHA256-CONFIRMED-SEAL-9021A", "/api/v1/lineage/documents/sample/download");
    }

    public AncestryTree fallbackAncestryTraversal(String nationalId, int depth, Throwable cause) {
        log.warn("Resilience4j CircuitBreaker fallback triggered for nationalId={}. Cause: {}", maskNationalId(nationalId), cause.getMessage());

        AncestorPerson root = new AncestorPerson(
                maskNationalId(nationalId),
                "CITIZEN",
                "RECORD",
                "UNKNOWN",
                "UNKNOWN",
                LocalDate.of(1985, 1, 1),
                "UNKNOWN",
                "DEGRADED_MODE",
                "KENDİSİ"
        );

        return new AncestryTree(root, List.of(), 0, "SHA256-DEGRADED-SEAL", null);
    }

    private String maskNationalId(String nationalId) {
        if (nationalId == null || nationalId.length() < 11) {
            return "123*****901";
        }
        return nationalId.substring(0, 3) + "*****" + nationalId.substring(8);
    }
}
