package io.casehub.desiredstate.ts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TsEnvelope(
        String kind,
        String namespace,
        String name,
        List<TsEnvelopeNode> nodes,
        List<DependencyDescriptor> dependencies,
        List<TsOrderingConstraint> orderingConstraints) {

    public TsEnvelope {
        if (nodes == null) {nodes = List.of();}
        if (dependencies == null) {dependencies = List.of();}
        if (orderingConstraints == null) {orderingConstraints = List.of();}
    }
}
