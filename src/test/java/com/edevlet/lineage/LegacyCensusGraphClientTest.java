package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.infrastructure.client.LegacyCensusGraphClientImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("fallbackAncestryTraversal - Returns degraded cached record when census graph fails")
    void testFallbackAncestryTraversal() {
        AncestryTree fallbackTree = client.fallbackAncestryTraversal("12345678950", 3, new RuntimeException("DB Timeout"));

        assertNotNull(fallbackTree);
        assertEquals("CITIZEN", fallbackTree.rootPerson().firstName());
        assertEquals("DEGRADED_MODE", fallbackTree.rootPerson().status());
        assertEquals("SHA256-DEGRADED-SEAL", fallbackTree.verificationSealHash());
    }
}
