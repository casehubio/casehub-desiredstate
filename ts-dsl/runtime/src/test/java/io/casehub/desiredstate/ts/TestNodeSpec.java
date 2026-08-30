package io.casehub.desiredstate.ts;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record TestNodeSpec(String value) implements NodeSpec {

    @Override
    public NodeType nodeType() {
        return NodeType.of("test-type");
    }
}
