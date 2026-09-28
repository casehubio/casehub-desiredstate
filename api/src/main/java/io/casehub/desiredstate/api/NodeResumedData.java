package io.casehub.desiredstate.api;

import java.util.Objects;

public record NodeResumedData(
        String tenancyId, String nodeId, String nodeType,
        long graphVersion, String parentNodeId) {
    public NodeResumedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(nodeType, "nodeType");
    }
}
