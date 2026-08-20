package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record UnitSpec(NodeId cellId, int strength) implements NodeSpec {
    @Override
    public NodeType nodeType() {return SpatialNodeTypes.UNIT;}
}
