package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeDriftedData(
        String tenancyId, String nodeId, String nodeType,
        long graphVersion, String parentNodeId) {
    public NodeDriftedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
    }
}
