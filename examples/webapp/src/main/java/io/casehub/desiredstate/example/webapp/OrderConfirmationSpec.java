package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("order-confirmation")
public record OrderConfirmationSpec(String emailTemplate, boolean smsEnabled) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.ORDER_CONFIRMATION; }
}
