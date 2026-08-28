package io.casehub.desiredstate.example.webapp;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("support-ticket")
public record SupportTicketSpec(String targetNodeId, String errorDetail, String priority) implements NodeSpec {
    @Override
    public NodeType nodeType() { return StoreNodeTypes.SUPPORT_TICKET; }
}
