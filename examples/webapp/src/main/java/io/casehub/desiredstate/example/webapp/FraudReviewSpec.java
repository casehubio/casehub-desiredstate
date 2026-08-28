package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("fraud-review")
public record FraudReviewSpec(String targetNodeId, String errorDetail) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.FRAUD_REVIEW; }
}
