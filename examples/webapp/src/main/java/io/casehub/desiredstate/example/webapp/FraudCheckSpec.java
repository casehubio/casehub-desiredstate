package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("fraud-check")
public record FraudCheckSpec(double riskThreshold, String provider) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.FRAUD_CHECK; }
}
