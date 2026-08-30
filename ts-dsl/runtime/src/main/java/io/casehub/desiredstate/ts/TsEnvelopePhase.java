package io.casehub.desiredstate.ts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TsEnvelopePhase(
        String id,
        Object completionCondition,
        List<TsEnvelopeNode> nodes,
        List<DependencyDescriptor> dependencies) {

    public TsEnvelopePhase {
        if (nodes == null) nodes = List.of();
        if (dependencies == null) dependencies = List.of();
    }
}
