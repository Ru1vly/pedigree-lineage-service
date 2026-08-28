package com.edevlet.lineage.infrastructure.client;

import com.edevlet.lineage.domain.model.AncestryTree;

public interface LegacyCensusGraphClient {
    AncestryTree traverseAncestryGraph(String nationalId, int depth);
}
