package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicNodeProvisionerRouter implements NodeProvisionerRouter {

    private final ConcurrentHashMap<NodeType, NodeProvisioner> routing = new ConcurrentHashMap<>();

    public void register(NodeProvisioner provisioner) {
        Set<NodeType> types = provisioner.handledTypes();
        for (NodeType type : types) {
            if (routing.containsKey(type)) {
                throw new IllegalStateException(
                        "Type collision: " + type.value() + " already handled by " + routing.get(type).getClass().getName());
            }
        }
        for (NodeType type : types) {
            routing.put(type, provisioner);
        }
    }

    public void unregister(NodeType type) {
        routing.remove(type);
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new ProvisionResult.Failed("No provisioner for type: " + node.type().value());
        }
        return p.provision(node, context);
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new DeprovisionResult.Failed("No provisioner for type: " + node.type().value());
        }
        return p.deprovision(node, context);
    }

    @Override
    public SuspendResult suspend(DesiredNode node, SuspendContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new SuspendResult.Failed("No provisioner for type: " + node.type().value());
        }
        return p.suspend(node, context);
    }

    @Override
    public ResumeResult resume(DesiredNode node, ResumeContext context) {
        NodeProvisioner p = routing.get(node.type());
        if (p == null) {
            return new ResumeResult.Failed("No provisioner for type: " + node.type().value());
        }
        return p.resume(node, context);
    }

    @Override
    public Duration resyncIntervalFor(NodeType type) {
        NodeProvisioner p = routing.get(type);
        return p != null ? p.resyncInterval() : Duration.ofMinutes(5);
    }

    @Override
    public Set<NodeType> allHandledTypes() {
        return Set.copyOf(routing.keySet());
    }

    @Override
    public boolean supportsStatefulLifecycle(NodeType type) {
        NodeProvisioner p = routing.get(type);
        return p != null && p.supportsStatefulLifecycle();
    }

    @Override
    public OptionalInt maxConcurrencyFor(NodeType type) {
        NodeProvisioner p = routing.get(type);
        return p != null ? p.maxConcurrency() : OptionalInt.empty();
    }
}
