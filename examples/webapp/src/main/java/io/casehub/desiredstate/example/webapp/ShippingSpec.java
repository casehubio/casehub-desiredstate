package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("shipping")
public record ShippingSpec(String carrier, String warehouse, boolean trackingEnabled) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.SHIPPING; }
}
