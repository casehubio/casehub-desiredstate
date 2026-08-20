package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record PatrolSpec(String locationId) implements NodeSpec {

    @Override
    public NodeType nodeType() {return ExpansionNodeTypes.PATROL;}
}
