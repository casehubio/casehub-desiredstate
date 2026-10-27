package io.casehub.desiredstate.bridge;

import java.util.List;

public record BridgePlan(
    String planId,
    String summary,
    List<ResourceChange> resourceChanges,
    Object rawPlan
) {}
