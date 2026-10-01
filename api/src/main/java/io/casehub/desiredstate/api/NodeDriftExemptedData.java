package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeDriftExemptedData(
        String tenancyId, String nodeId, String nodeType,
        String revertMode, String revertCondition,
        long graphVersion, String parentNodeId) {
    public NodeDriftExemptedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
        Objects.requireNonNull(revertMode, "revertMode");
    }
}
