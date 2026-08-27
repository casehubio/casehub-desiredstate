package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

@NodeTypeId("data-source")
public record DataSourceSpec(String name, String format, String uri) implements NodeSpec {
    @Override
    public NodeType nodeType() {return PipelineNodeTypes.DATA_SOURCE;}
}
