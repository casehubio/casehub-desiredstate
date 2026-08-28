package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("monitor")
public record MonitorSpec(String target) implements NodeSpec {
    @Override
    public NodeType nodeType() { return NodeType.of("monitor"); }
}
