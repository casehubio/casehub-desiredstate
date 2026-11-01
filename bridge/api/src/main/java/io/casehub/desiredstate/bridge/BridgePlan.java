package io.casehub.desiredstate.bridge;

import java.util.List;

public record BridgePlan(
        String planId,
        String tenancyId,
        String typeId,
        String summary,
        List<ResourceChange> resourceChanges,
        Object rawPlan
) {
    public BridgePlan(String planId, String summary, List<ResourceChange> resourceChanges, Object rawPlan) {
        this(planId, null, null, summary, resourceChanges, rawPlan);
    }
}
