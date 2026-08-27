package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

import java.util.List;

@NodeTypeId("transformer")
public record TransformerSpec(List<String> aggregations, List<String> reshapeRules,
                              String outputFormat, boolean approvalRequired) implements NodeSpec {
    public TransformerSpec {
        aggregations = List.copyOf(aggregations);
        reshapeRules = List.copyOf(reshapeRules);
    }

    public TransformerSpec(List<String> aggregations, List<String> reshapeRules, String outputFormat) {
        this(aggregations, reshapeRules, outputFormat, false);
    }

    @Override
    public HumanGating humanGating() {
        return approvalRequired ? HumanGating.DEPROVISION_ONLY : HumanGating.NONE;
    }

    @Override
    public NodeType nodeType() {return PipelineNodeTypes.TRANSFORMER;}
}
