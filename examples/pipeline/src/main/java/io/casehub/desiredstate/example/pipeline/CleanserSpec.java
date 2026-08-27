package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

import java.util.List;

@NodeTypeId("cleanser")
public record CleanserSpec(List<String> rules, boolean deduplication, String nullHandling) implements NodeSpec {
    public CleanserSpec {
        rules = List.copyOf(rules);
    }

    @Override
    public NodeType nodeType() {return PipelineNodeTypes.CLEANSER;}
}
