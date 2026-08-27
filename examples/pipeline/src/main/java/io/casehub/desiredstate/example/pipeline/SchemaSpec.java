package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

import java.util.List;

@NodeTypeId("schema")
public record SchemaSpec(String name, List<String> fields, int version) implements NodeSpec {
    public SchemaSpec {
        fields = List.copyOf(fields);
    }

    @Override
    public NodeType nodeType() {return PipelineNodeTypes.SCHEMA;}
}
