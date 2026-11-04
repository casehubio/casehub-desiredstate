package io.casehub.desiredstate.api;

import java.util.Map;

public record MapNodeSpec(NodeType nodeType, Map<String, Object> fields)
        implements NodeSpec {

    public MapNodeSpec {
        if (fields == null) fields = Map.of();
    }

    @Override
    public NodeType nodeType() {
        return nodeType;
    }
}
