package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Objects;

public record ReconciliationCompletedData(
        String tenancyId, long graphVersion,
        int nodeCount, int additionsCount, int removalsCount, int faultCount,
        Instant timestamp) {
    public ReconciliationCompletedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (nodeCount < 0 || additionsCount < 0 || removalsCount < 0 || faultCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }
}
