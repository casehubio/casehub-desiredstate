package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;

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
    public final CopyOnWriteArrayList<DesiredNode> suspended     = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<DesiredNode> resumed       = new CopyOnWriteArrayList<>();


    private Function<DesiredNode, ProvisionResult> provisionBehavior = node -> new ProvisionResult.Success();
    private Function<DesiredNode, DeprovisionResult> deprovisionBehavior = node -> new DeprovisionResult.Success();
    private Function<DesiredNode, SuspendResult>     suspendBehavior     = node -> new SuspendResult.Success();
    private Function<DesiredNode, ResumeResult>      resumeBehavior      = node -> new ResumeResult.Success();
    private boolean                                  statefulLifecycle   = false;

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

    @Override
    public SuspendResult suspend(DesiredNode node, SuspendContext context) {
        suspended.add(node);
        return suspendBehavior.apply(node);
    }

    @Override
    public ResumeResult resume(DesiredNode node, ResumeContext context) {
        resumed.add(node);
        return resumeBehavior.apply(node);
    }

    @Override
    public boolean supportsStatefulLifecycle() {return statefulLifecycle;}

    public void setSuspendBehavior(Function<DesiredNode, SuspendResult> behavior) {
        this.suspendBehavior = behavior;
    }

    public void setResumeBehavior(Function<DesiredNode, ResumeResult> behavior) {
        this.resumeBehavior = behavior;
    }

    public void setStatefulLifecycle(boolean stateful) {this.statefulLifecycle = stateful;}


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
        suspended.clear();
        resumed.clear();
        provisionBehavior   = node -> new ProvisionResult.Success();
        deprovisionBehavior = node -> new DeprovisionResult.Success();
        suspendBehavior     = node -> new SuspendResult.Success();
        resumeBehavior      = node -> new ResumeResult.Success();
        handledTypes        = Set.of();
        resyncInterval      = Duration.ofMinutes(5);
        statefulLifecycle   = false;
    }
}
