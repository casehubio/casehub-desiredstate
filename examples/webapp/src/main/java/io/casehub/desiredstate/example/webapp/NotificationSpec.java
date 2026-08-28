package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("notification")
public record NotificationSpec(String channel, String target) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.NOTIFICATION; }
}
