package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeFaultedData(
        String tenancyId, String nodeId, String nodeType,
        String faultType, String reason, long graphVersion,
        String parentNodeId) {
    public NodeFaultedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(faultType, "faultType");
    }
}
