package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record AiReviewSpec(NodeId targetNodeId, String errorDetail) implements NodeSpec {
    @Override
    public NodeType nodeType() {return PipelineNodeTypes.AI_REVIEW;}
}
