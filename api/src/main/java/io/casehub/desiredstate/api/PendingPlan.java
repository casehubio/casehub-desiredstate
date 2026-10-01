package io.casehub.desiredstate.api;

import java.util.Objects;

public record PendingPlan(TransitionPlan plan, String planReference, int desiredVersion) {
    public PendingPlan {
        Objects.requireNonNull(plan, "PendingPlan.plan must not be null");
        Objects.requireNonNull(planReference, "PendingPlan.planReference must not be null");
    }
}
