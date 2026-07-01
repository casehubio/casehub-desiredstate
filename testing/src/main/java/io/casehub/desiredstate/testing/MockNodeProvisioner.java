package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Mock NodeProvisioner for testing. Records all provision/deprovision calls.
 * Configurable results via setter methods (default: Success).
 */
public class MockNodeProvisioner implements NodeProvisioner {

    /**
     * All nodes provisioned, in order. Public for test assertions.
     */
    public final CopyOnWriteArrayList<DesiredNode> provisioned = new CopyOnWriteArrayList<>();

    /**
     * All nodes deprovisioned, in order. Public for test assertions.
     */
    public final CopyOnWriteArrayList<DesiredNode> deprovisioned = new CopyOnWriteArrayList<>();

    private Function<DesiredNode, ProvisionResult> provisionBehavior = node -> new ProvisionResult.Success();
    private Function<DesiredNode, DeprovisionResult> deprovisionBehavior = node -> new DeprovisionResult.Success();
    private Set<NodeType> handledTypes = Set.of();
    private Duration resyncInterval = Duration.ofMinutes(5);

    @Override
    public Set<NodeType> handledTypes() {
        return handledTypes;
    }

    @Override
    public Duration resyncInterval() {
        return resyncInterval;
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        provisioned.add(node);
        return provisionBehavior.apply(node);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        deprovisioned.add(node);
        return deprovisionBehavior.apply(node);
    }

    public void setProvisionBehavior(Function<DesiredNode, ProvisionResult> behavior) {
        this.provisionBehavior = behavior;
    }

    public void setDeprovisionBehavior(Function<DesiredNode, DeprovisionResult> behavior) {
        this.deprovisionBehavior = behavior;
    }

    public void setHandledTypes(Set<NodeType> types) { this.handledTypes = Set.copyOf(types); }

    public void setResyncInterval(Duration interval) { this.resyncInterval = interval; }

    public void clear() {
        provisioned.clear();
        deprovisioned.clear();
        provisionBehavior = node -> new ProvisionResult.Success();
        deprovisionBehavior = node -> new DeprovisionResult.Success();
        handledTypes = Set.of();
        resyncInterval = Duration.ofMinutes(5);
    }
}
