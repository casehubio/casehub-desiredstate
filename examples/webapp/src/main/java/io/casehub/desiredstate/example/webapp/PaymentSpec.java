package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("payment")
public record PaymentSpec(String provider, String currency, int maxRetries) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.PAYMENT; }
}
