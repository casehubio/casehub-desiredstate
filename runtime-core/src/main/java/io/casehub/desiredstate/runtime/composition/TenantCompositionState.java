package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.DomainId;

import java.util.LinkedHashMap;
import java.util.Map;

record TenantCompositionState(Map<DomainId, DomainPhaseState> phases) {
    TenantCompositionState withPhase(DomainId id, DomainPhaseState newPhase) {
        var copy = new LinkedHashMap<>(phases);
        copy.put(id, newPhase);
        return new TenantCompositionState(Map.copyOf(copy));
    }
}
