package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.eidos.org.api.AgentRelationship;

@NodeTypeId("org:relationship")
public record OrgRelationshipNodeSpec(AgentRelationship relationship) implements NodeSpec {
    @Override
    public NodeType nodeType() {return OrgNodeTypes.RELATIONSHIP;}
}
