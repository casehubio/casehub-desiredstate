package io.casehub.desiredstate.api;

import java.util.Objects;

/**
 * Context passed to a provisioner when provisioning a node.
 * Carries tenancy identity, the full desired-state graph for reference,
 * and optional approval context when re-entering after a PendingApproval cycle.
 */
public record ProvisionContext(String tenancyId, DesiredStateGraph graph, PlanApproval approval) {

    public ProvisionContext {
        Objects.requireNonNull(tenancyId, "tenancyId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
    }

    public ProvisionContext(String tenancyId, DesiredStateGraph graph) {
        this(tenancyId, graph, null);
    }

    public boolean hasApproval() {
        return approval != null;
    }

    public ProvisionContext withApproval(PlanApproval approval) {
        return new ProvisionContext(this.tenancyId, this.graph, approval);
    }
}
