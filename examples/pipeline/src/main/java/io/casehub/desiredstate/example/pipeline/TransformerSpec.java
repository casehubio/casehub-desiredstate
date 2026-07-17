package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

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
}
