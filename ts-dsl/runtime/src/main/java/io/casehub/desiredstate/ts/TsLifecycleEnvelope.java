package io.casehub.desiredstate.ts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TsLifecycleEnvelope(
        String kind,
        String namespace,
        String name,
        List<TsEnvelopePhase> phases,
        List<TsOrderingConstraint> orderingConstraints) {

    public TsLifecycleEnvelope {
        if (phases == null) {phases = List.of();}
        if (orderingConstraints == null) {orderingConstraints = List.of();}
    }
}
