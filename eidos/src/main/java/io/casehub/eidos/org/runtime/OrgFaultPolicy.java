package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.util.Set;

@ApplicationScoped
public class OrgFaultPolicy {

    @Produces
    @ApplicationScoped
    public ThresholdFaultPolicy orgFaultPolicy(FaultCountStore faultCountStore) {
        return create(faultCountStore);
    }

    static ThresholdFaultPolicy create(FaultCountStore faultCountStore) {
        return ThresholdFaultPolicy.builder()
            .faultTypes(Set.of(FaultType.PROVISION_FAILED, FaultType.DEPROVISION_FAILED))
            .nodeTypes(Set.of(OrgNodeTypes.UNIT))
            .tier(3, FaultPolicy.addReviewNode((event, graph) ->
                new OrgReviewNodeSpec(event.node().value(), event.detail())))
            .tier(5, FaultPolicy.addReviewNode((event, graph) ->
                new OrgHumanReviewNodeSpec(event.node().value(), event.detail())))
            .faultCountStore(faultCountStore)
            .namespace("org-faults")
            .build();
    }
}
