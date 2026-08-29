package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClientImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LegacyCensusGraphClientTest {

    private LegacyCensusGraphClientImpl client;

    @BeforeEach
    void setUp() {
        client = new LegacyCensusGraphClientImpl();
    }

    @Test
    @DisplayName("traverseAncestryGraph - Successfully returns ancestry tree graph nodes")
    void testTraverseAncestryGraph_Success() {
        AncestryTree tree = client.traverseAncestryGraph("12345678950", 3);

        assertNotNull(tree);
        assertNotNull(tree.rootPerson());
        assertEquals("AHMET", tree.rootPerson().firstName());
        assertEquals(2, tree.generations().size());
        assertEquals("SHA256-CONFIRMED-SEAL-9021A", tree.verificationSealHash());
    }

    @Test
    @DisplayName("traverseAncestryGraph - A backend failure propagates instead of returning invented records")
    void testBackendFailurePropagates() {
        // The client used to answer an unreachable backend with a fabricated "DEGRADED_MODE" tree.
        // Nothing downstream could tell that apart from a real answer, so the pipeline marked the
        // task COMPLETED and the document endpoint rendered those invented ancestors as a certified
        // pedigree document. Failing loudly is the whole point - the caller has to be told.
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> client.traverseAncestryGraph("99999999999", 3));

        assertTrue(thrown.getMessage().contains("timeout"),
                "the real cause must survive, not be swallowed into a degraded result");
    }

    @Test
    @DisplayName("No fallback method exists on the client for the circuit breaker to resolve")
    void testNoFallbackMethodExists() {
        // @CircuitBreaker resolves fallbacks reflectively by name, so a re-added method would wire
        // itself back up silently. This asserts the absence structurally rather than by convention.
        assertTrue(Arrays.stream(LegacyCensusGraphClientImpl.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().toLowerCase().contains("fallback")),
                "a fallback that substitutes ancestry must not be reintroduced");
    }
}
