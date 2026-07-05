package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeRecoveredData(
        String tenancyId, String nodeId, String nodeType,
        long graphVersion, String parentNodeId) {
    public NodeRecoveredData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
    }
}
