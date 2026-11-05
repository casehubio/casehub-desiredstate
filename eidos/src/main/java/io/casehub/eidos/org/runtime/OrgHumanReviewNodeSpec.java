package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("org:human-review")
public record OrgHumanReviewNodeSpec(String faultedNodeId, String faultDetail) implements NodeSpec {
    static final NodeType TYPE = NodeType.of("org:human-review");

    @Override
    public NodeType nodeType() { return TYPE; }
}
