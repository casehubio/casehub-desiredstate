package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record ScoutSpec(NodeId cellId, int visionRange) implements NodeSpec {
    @Override
    public NodeType nodeType() {return SpatialNodeTypes.SCOUT;}
}
