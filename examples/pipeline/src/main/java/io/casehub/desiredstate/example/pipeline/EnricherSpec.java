package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

import java.util.List;

@NodeTypeId("enricher")
public record EnricherSpec(String lookupSource, List<String> joinKeys, List<String> enrichFields) implements NodeSpec {
    public EnricherSpec {
        joinKeys     = List.copyOf(joinKeys);
        enrichFields = List.copyOf(enrichFields);
    }

    @Override
    public NodeType nodeType() {return PipelineNodeTypes.ENRICHER;}
}
