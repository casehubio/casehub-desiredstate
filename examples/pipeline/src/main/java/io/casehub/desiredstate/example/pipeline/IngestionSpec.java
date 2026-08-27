package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

@NodeTypeId("ingestion")
public record IngestionSpec(String sourceRef, int batchSize, String format) implements NodeSpec {
    @Override
    public NodeType nodeType() {return PipelineNodeTypes.INGESTION;}
}
