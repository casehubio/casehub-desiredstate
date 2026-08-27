package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.NodeType;

import java.util.List;

@NodeTypeId("sink")
public record SinkSpec(String destination, String format, List<String> partitionKeys,
                       boolean approvalRequired) implements NodeSpec {
    public SinkSpec {
        partitionKeys = List.copyOf(partitionKeys);
    }

    public SinkSpec(String destination, String format, List<String> partitionKeys) {
        this(destination, format, partitionKeys, false);
    }

    @Override
    public HumanGating humanGating() {
        return approvalRequired ? HumanGating.DEPROVISION_ONLY : HumanGating.NONE;
    }

    @Override
    public NodeType nodeType() {return PipelineNodeTypes.SINK;}
}
