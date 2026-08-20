package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import java.util.Map;
import java.util.Objects;

public record ZoneSpec(String zoneName, Map<NodeId, Double> allocation, int totalForce)
        implements NodeSpec {
    public ZoneSpec {
        Objects.requireNonNull(zoneName);
        allocation = Map.copyOf(allocation);
        if (totalForce < 0)
            throw new IllegalArgumentException("totalForce must be >= 0");
    }

    public int strengthFor(NodeId cellId) {
        return (int) Math.round(totalForce * allocation.getOrDefault(cellId, 0.0));
    }

    @Override
    public NodeType nodeType() {return SpatialNodeTypes.ZONE;}
}
