package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

@NodeTypeId("human-review")
public record HumanReviewSpec(NodeId targetNodeId, String errorDetail, String escalationReason) implements NodeSpec {
    @Override
    public NodeType nodeType() {return PipelineNodeTypes.HUMAN_REVIEW;}
}
