package io.casehub.desiredstate.api;

import java.util.Map;

public record DiscoveredNode(
    String resourceId,
    NodeType type,
    Map<String, Object> state,
    Map<String, Object> spec
) {
    public DiscoveredNode {
        if (state == null) state = Map.of();
        if (spec == null) spec = Map.of();
    }

    public MapNodeSpec toNodeSpec() {
        return new MapNodeSpec(type, spec);
    }
}
