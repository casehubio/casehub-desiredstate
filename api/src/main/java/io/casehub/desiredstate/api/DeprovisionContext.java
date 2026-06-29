package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Context passed to a provisioner when deprovisioning a node.
 * Carries tenancy identity, the full desired-state graph for reference,
 * and optional approval context when re-entering after a PendingApproval cycle.
 */
public record DeprovisionContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {

    public DeprovisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public DeprovisionContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public DeprovisionContext withApproval(PlanApproval approval) {
        return new DeprovisionContext(this.tenancyId, this.graph, approval);
    }
}
