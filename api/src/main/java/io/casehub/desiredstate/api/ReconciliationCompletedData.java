package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ReconciliationCompletedData(
        String tenancyId, long graphVersion,
        int nodeCount, int additionsCount, int removalsCount,
        int suspensionsCount, int resumptionsCount,
        int faultCount, int exemptedCount,
        Map<String, String> nodeOutcomes, Instant timestamp) {

    public static final int NODE_OUTCOMES_THRESHOLD = 1000;

    public ReconciliationCompletedData(String tenancyId, long graphVersion,
                                       int nodeCount, int additionsCount, int removalsCount,
                                       int faultCount, Instant timestamp) {
        this(tenancyId, graphVersion, nodeCount, additionsCount, removalsCount,
             0, 0, faultCount, 0, Map.of(), timestamp);
    }

    public ReconciliationCompletedData(String tenancyId, long graphVersion,
                                       int nodeCount, int additionsCount, int removalsCount,
                                       int suspensionsCount, int resumptionsCount,
                                       int faultCount, Instant timestamp) {
        this(tenancyId, graphVersion, nodeCount, additionsCount, removalsCount,
             suspensionsCount, resumptionsCount, faultCount, 0, Map.of(), timestamp);
    }

    public ReconciliationCompletedData(String tenancyId, long graphVersion,
                                       int nodeCount, int additionsCount, int removalsCount,
                                       int suspensionsCount, int resumptionsCount,
                                       int faultCount, Map<String, String> nodeOutcomes,
                                       Instant timestamp) {
        this(tenancyId, graphVersion, nodeCount, additionsCount, removalsCount,
             suspensionsCount, resumptionsCount, faultCount, 0, nodeOutcomes, timestamp);
    }

    public ReconciliationCompletedData {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(nodeOutcomes, "nodeOutcomes");
        nodeOutcomes = Map.copyOf(nodeOutcomes);
        if (nodeCount < 0 || additionsCount < 0 || removalsCount < 0
            || suspensionsCount < 0 || resumptionsCount < 0 || faultCount < 0
            || exemptedCount < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
    }
}
