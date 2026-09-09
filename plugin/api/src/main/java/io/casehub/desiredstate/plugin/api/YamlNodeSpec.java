package io.casehub.desiredstate.plugin.api;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

import java.util.Map;
import java.util.Objects;

public final class YamlNodeSpec implements NodeSpec {

    private final NodeType nodeType;
    private final HumanGating humanGating;
    private final Map<String, Object> fields;

    public YamlNodeSpec(NodeType nodeType, HumanGating humanGating,
                        Map<String, Object> fields) {
        this.nodeType = Objects.requireNonNull(nodeType);
        this.humanGating = Objects.requireNonNull(humanGating);
        this.fields = Map.copyOf(fields);
    }

    @Override
    public NodeType nodeType() {
        return nodeType;
    }

    @Override
    public HumanGating humanGating() {
        return humanGating;
    }

    public Object get(String fieldName) {
        return fields.get(fieldName);
    }

    public Map<String, Object> fields() {
        return fields;
    }
}
