package io.casehub.desiredstate.bridge;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import java.util.Map;

public record BridgeNodeSpec(NodeType nodeType, Map<String, Object> fields) implements NodeSpec {
    public BridgeNodeSpec {
        if (fields == null) fields = Map.of();
    }

    public String importId() {
        Object value = fields().get("import");
        return value instanceof String s ? s : null;
    }
}
