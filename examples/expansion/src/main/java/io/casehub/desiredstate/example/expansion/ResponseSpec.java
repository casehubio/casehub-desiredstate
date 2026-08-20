package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record ResponseSpec(String locationId, DefensePosture posture) implements NodeSpec {

    @Override
    public NodeType nodeType() {return ExpansionNodeTypes.RESPONSE;}
}
