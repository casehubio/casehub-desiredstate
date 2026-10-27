package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.bridge.*;

import java.util.Map;
import java.util.Set;

public class BridgeProvisioner implements NodeProvisioner {

    private final BridgeDescriptor descriptor;
    private final BridgeProvider provider;
    private final BridgeOutputStateStore stateStore;

    public BridgeProvisioner(BridgeDescriptor descriptor, BridgeProvider provider) {
        this(descriptor, provider, new NoOpBridgeOutputStateStore());
    }

    public BridgeProvisioner(BridgeDescriptor descriptor, BridgeProvider provider,
                             BridgeOutputStateStore stateStore) {
        this.descriptor = descriptor;
        this.provider = provider;
        this.stateStore = stateStore;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return descriptor.handledTypes();
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        var request = toBridgeRequest(node, context.tenancyId());

        BridgeResult result;
        if (provider instanceof PlanCapable planCapable && context.approval() == null) {
            var plan = planCapable.plan(request);
            result = planCapable.applyPlan(plan);
        } else {
            result = provider.apply(request);
        }

        return switch (result) {
            case BridgeResult.Success s -> {
                stateStore.store(context.tenancyId(), node.id().value(), s.outputState());
                yield new ProvisionResult.Success();
            }
            case BridgeResult.Failed f -> new ProvisionResult.Failed(f.reason());
            case BridgeResult.PendingApproval p -> new ProvisionResult.PendingApproval(
                node.id(), p.planReference());
        };
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        var request = toBridgeRequest(node, context.tenancyId());
        var result = provider.destroy(request);
        return switch (result) {
            case BridgeResult.Success s -> {
                stateStore.remove(context.tenancyId(), node.id().value());
                yield new DeprovisionResult.Success();
            }
            case BridgeResult.Failed f -> new DeprovisionResult.Failed(f.reason());
            case BridgeResult.PendingApproval p -> new DeprovisionResult.PendingApproval(
                node.id(), p.planReference());
        };
    }

    @Override
    public SuspendResult suspend(DesiredNode node, SuspendContext context) {
        if (provider instanceof SuspendCapable sc) {
            var request = toBridgeRequest(node, context.tenancyId());
            var result = sc.suspend(request);
            return switch (result) {
                case BridgeResult.Success s -> new SuspendResult.Success();
                case BridgeResult.Failed f -> new SuspendResult.Failed(f.reason());
                case BridgeResult.PendingApproval p -> new SuspendResult.PendingApproval(
                    node.id(), p.planReference());
            };
        }
        return new SuspendResult.Failed("Provider does not support suspend");
    }

    @Override
    public ResumeResult resume(DesiredNode node, ResumeContext context) {
        if (provider instanceof SuspendCapable sc) {
            var request = toBridgeRequest(node, context.tenancyId());
            var result = sc.resume(request);
            return switch (result) {
                case BridgeResult.Success s -> new ResumeResult.Success();
                case BridgeResult.Failed f -> new ResumeResult.Failed(f.reason());
                case BridgeResult.PendingApproval p -> new ResumeResult.PendingApproval(
                    node.id(), p.planReference());
            };
        }
        return new ResumeResult.Failed("Provider does not support resume");
    }

    @Override
    public boolean supportsStatefulLifecycle() {
        return provider instanceof SuspendCapable;
    }

    private BridgeRequest toBridgeRequest(DesiredNode node, String tenancyId) {
        Map<String, Object> fields = node.spec() instanceof BridgeNodeSpec bns
            ? bns.fields()
            : Map.of();
        Map<String, Object> previousState = stateStore.load(tenancyId, node.id().value())
            .orElse(Map.of());
        return new BridgeRequest(node.type().value(), node.id().value(), fields, tenancyId, previousState);
    }
}
