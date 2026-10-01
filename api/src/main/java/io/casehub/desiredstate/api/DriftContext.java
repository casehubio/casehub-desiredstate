package io.casehub.desiredstate.api;

import java.util.Objects;

public record DriftContext(String tenancyId, DesiredStateGraph graph,
                           ActualState actual) {
    public DriftContext {
        Objects.requireNonNull(tenancyId, "tenancyId");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(actual, "actual");
    }
}
