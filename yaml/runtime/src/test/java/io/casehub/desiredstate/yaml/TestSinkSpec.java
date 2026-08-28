package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("sink")
public record TestSinkSpec(String destination) implements NodeSpec {
    @Override
    public NodeType nodeType() { return NodeType.of("sink"); }
}
