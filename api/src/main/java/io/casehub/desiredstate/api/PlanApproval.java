package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record PlanApproval(String planReference, String approvedBy, Instant approvedAt) {
    public PlanApproval {
        Objects.requireNonNull(planReference, "planReference must not be null");
        Objects.requireNonNull(approvedBy, "approvedBy must not be null");
        Objects.requireNonNull(approvedAt, "approvedAt must not be null");
    }
}
