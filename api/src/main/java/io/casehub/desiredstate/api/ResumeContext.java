package io.casehub.desiredstate.api;

import java.util.Objects;

public record ResumeContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {

    public ResumeContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public ResumeContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public ResumeContext withApproval(PlanApproval approval) {
        return new ResumeContext(this.tenancyId, this.graph, approval);
    }
}
