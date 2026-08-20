package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record ValidatorSpec(String schemaRef, double qualityThreshold, boolean anomalyDetection) implements NodeSpec {
    @Override
    public NodeType nodeType() {return PipelineNodeTypes.VALIDATOR;}
}
