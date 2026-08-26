package io.casehub.desiredstate.example.pipeline.annotated;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record MonitorSpec(String target) implements NodeSpec {
    @Override
    public NodeType nodeType() { return NodeType.of("monitor"); }
}
