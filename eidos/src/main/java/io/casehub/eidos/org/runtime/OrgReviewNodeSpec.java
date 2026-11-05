package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;

@NodeTypeId("org:review")
public record OrgReviewNodeSpec(String faultedNodeId, String faultDetail) implements NodeSpec {
    static final NodeType TYPE = NodeType.of("org:review");

    @Override
    public NodeType nodeType() { return TYPE; }
}
