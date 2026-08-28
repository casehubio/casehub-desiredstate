package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("loyalty")
public record LoyaltySpec(int pointsPerDollar, String tier) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.LOYALTY; }
}
