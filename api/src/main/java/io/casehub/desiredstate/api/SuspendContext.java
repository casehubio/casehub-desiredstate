package io.casehub.desiredstate.api;

import java.util.Objects;

public record SuspendContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {

    public SuspendContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public SuspendContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public SuspendContext withApproval(PlanApproval approval) {
        return new SuspendContext(this.tenancyId, this.graph, approval);
    }
}
