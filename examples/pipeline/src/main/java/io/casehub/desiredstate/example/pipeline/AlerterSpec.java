package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("alerter")
public record AlerterSpec(String email) implements NodeSpec {
    @Override
    public NodeType nodeType() { return NodeType.of("alerter"); }
}
