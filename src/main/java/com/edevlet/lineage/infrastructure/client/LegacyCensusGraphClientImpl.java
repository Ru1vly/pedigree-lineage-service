package com.edevlet.lineage.infrastructure.client;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.AncestryTree.AncestorPerson;
import com.edevlet.lineage.domain.model.AncestryTree.GenerationNode;
import com.edevlet.lineage.infrastructure.util.NationalIdMasker;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Simulated stand-in for the legacy census/family-registry graph backend. It returns a single fixed
 * family regardless of the national ID it is given - see the README's "What is simulated" section.
 * The circuit breaker, retry and failure semantics around it are real; the records are not.
 *
 * <p>There is deliberately NO fallback method. A fallback here returned an invented tree
 * ("CITIZEN RECORD" / "UNKNOWN" / a "SHA256-DEGRADED-SEAL" literal), and because nothing downstream
 * could distinguish it from a real answer the pipeline stamped the task COMPLETED and the document
 * endpoint rendered those fabricated ancestors under a footer citing Law No. 5070 on secure
 * electronic signatures. During a backend outage the service issued citizens official-looking
 * pedigree documents containing ancestry that does not exist, and recorded them as successes.
 *
 * <p>An open circuit now propagates. The orchestrator catches it, PipelineFailureHandler retries and
 * then records a terminal FAILED with the real cause plus its compliance audit row, and the record
 * reaches the dead-letter topic. That machinery already existed; the fallback was routing around it.
 * A backend outage is a real event and the caller has to be told - the same argument that makes
 * {@code VaultTransitTcknEncryptionService.decrypt} throw rather than hand back a value it could
 * not decrypt.
 *
 * <h2>Retry budget</h2>
 *
 * <p>{@code @Retry} below is the <em>inner</em> of two retry layers, and for a long time it was an
 * unconfigured one: {@code application.yml} declared a circuit breaker named
 * {@code legacyCensusBackend} but no {@code resilience4j.retry} instance, so this annotation ran on
 * the library's defaults (3 attempts, flat 500ms, retrying everything - including the circuit
 * breaker's own {@code CallNotPermittedException}, which is the one exception where retrying is
 * guaranteed pointless). The outer layer is the pipeline's re-queue loop, which fired with no delay
 * at all. Nothing multiplied the two together: worst case was around twelve calls per task into a
 * backend that was, by construction, already failing.
 *
 * <p>Both layers are now explicit and the product is written down in
 * {@code PipelineRetryProperties}: three pipeline attempts times two calls each, spread across
 * seconds of backoff, and an open circuit fails straight through instead of being retried.
 */
@Slf4j
@Component
public class LegacyCensusGraphClientImpl implements LegacyCensusGraphClient {

    @Override
    @CircuitBreaker(name = "legacyCensusBackend")
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

        // documentDownloadUrl is left null on purpose. The census backend is an upstream record
        // source; it does not host this service's documents and cannot know a task's download URL.
        // It previously returned the literal "/api/v1/lineage/documents/sample/download", so every
        // stored result payload pointed at a sample document rather than at its own - a link that
        // resolves for every citizen to the same wrong file. LineagePipelinePhaseRunner stamps the
        // real per-transaction URL onto the tree once it knows the transactionId.
        return new AncestryTree(root, generations, 5, "SHA256-CONFIRMED-SEAL-9021A", null);
    }

    private String maskNationalId(String nationalId) {
        return NationalIdMasker.mask(nationalId);
    }
}
