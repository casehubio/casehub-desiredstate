package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import java.util.List;

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
}
