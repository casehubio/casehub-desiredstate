package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeLifecycleDefinition;
import io.casehub.desiredstate.api.NodeLifecycleState;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;
import io.casehub.desiredstate.api.TransitionAction;
import io.casehub.yaml.core.orchestration.DefaultOrcStateMachine;
import io.casehub.yaml.core.orchestration.IllegalTransitionException;
import io.casehub.yaml.core.orchestration.OrcStateMachine;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.casehub.desiredstate.api.NodeLifecycleState.*;

public class StatefulNodeProvisioner implements NodeProvisioner {

    private static final Logger LOG = Logger.getLogger(StatefulNodeProvisioner.class.getName());

    private final NodeProvisioner delegate;
    private final NodeLifecycleDefinition lifecycle;
    private final TransitionActionHandler actionHandler;
    private final ConcurrentHashMap<MachineKey, OrcStateMachine<NodeLifecycleState>> machines = new ConcurrentHashMap<>();

    private record MachineKey(String tenancyId, NodeId nodeId) {}

    public StatefulNodeProvisioner(NodeProvisioner delegate,
                                   NodeLifecycleDefinition lifecycle,
                                   TransitionActionHandler actionHandler) {
        this.delegate = Objects.requireNonNull(delegate);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.actionHandler = Objects.requireNonNull(actionHandler);
    }

    public void initializeNodeState(NodeId nodeId, NodeLifecycleState state, String tenancyId) {
        machines.computeIfAbsent(new MachineKey(tenancyId, nodeId), k -> buildMachine(nodeId, state));
    }

    @Override
    public Set<NodeType> handledTypes() {
        return delegate.handledTypes();
    }

    @Override
    public Duration resyncInterval() {
        return delegate.resyncInterval();
    }

    @Override
    public OptionalInt maxConcurrency() {
        return delegate.maxConcurrency();
    }

    @Override
    public boolean supportsStatefulLifecycle() {
        return lifecycle.supportsSuspendResume();
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        var machine = getOrCreateMachine(node.id(), context.tenancyId(), ABSENT);

        if (!tryTransition(machine, machine.currentState(), PROVISIONING, node.id(), context.tenancyId())) {
            return new ProvisionResult.Failed(
                "lifecycle transition rejected: " + machine.currentState() + " → PROVISIONING");
        }

        ProvisionResult result = delegate.provision(node, context);

        if (result instanceof ProvisionResult.Success) {
            tryTransition(machine, PROVISIONING, PRESENT, node.id(), context.tenancyId());
        } else if (result instanceof ProvisionResult.Failed) {
            tryTransitionQuietly(machine, PROVISIONING, DRIFTED, node.id(), context.tenancyId());
        }

        return result;
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        var machine = getOrCreateMachine(node.id(), context.tenancyId(), PRESENT);

        if (!tryTransition(machine, machine.currentState(), DEPROVISIONING, node.id(), context.tenancyId())) {
            return new DeprovisionResult.Failed(
                "lifecycle transition rejected: " + machine.currentState() + " → DEPROVISIONING");
        }

        DeprovisionResult result = delegate.deprovision(node, context);

        if (result instanceof DeprovisionResult.Success) {
            tryTransition(machine, DEPROVISIONING, ABSENT, node.id(), context.tenancyId());
            machines.remove(new MachineKey(context.tenancyId(), node.id()));
        }

        return result;
    }

    @Override
    public SuspendResult suspend(DesiredNode node, SuspendContext context) {
        var machine = getOrCreateMachine(node.id(), context.tenancyId(), PRESENT);

        if (!tryTransition(machine, machine.currentState(), SUSPENDING, node.id(), context.tenancyId())) {
            return new SuspendResult.Failed(
                "lifecycle transition rejected: " + machine.currentState() + " → SUSPENDING");
        }

        SuspendResult result = delegate.suspend(node, context);

        if (result instanceof SuspendResult.Success) {
            tryTransition(machine, SUSPENDING, SUSPENDED, node.id(), context.tenancyId());
        } else if (result instanceof SuspendResult.Failed) {
            tryTransitionQuietly(machine, SUSPENDING, PRESENT, node.id(), context.tenancyId());
        }

        return result;
    }

    @Override
    public ResumeResult resume(DesiredNode node, ResumeContext context) {
        var machine = getOrCreateMachine(node.id(), context.tenancyId(), SUSPENDED);

        if (!tryTransition(machine, machine.currentState(), RESUMING, node.id(), context.tenancyId())) {
            return new ResumeResult.Failed(
                "lifecycle transition rejected: " + machine.currentState() + " → RESUMING");
        }

        ResumeResult result = delegate.resume(node, context);

        if (result instanceof ResumeResult.Success) {
            tryTransition(machine, RESUMING, PRESENT, node.id(), context.tenancyId());
        } else if (result instanceof ResumeResult.Failed) {
            tryTransitionQuietly(machine, RESUMING, SUSPENDED, node.id(), context.tenancyId());
        }

        return result;
    }

    private OrcStateMachine<NodeLifecycleState> getOrCreateMachine(NodeId nodeId, String tenancyId,
                                                                    NodeLifecycleState defaultInitial) {
        return machines.computeIfAbsent(new MachineKey(tenancyId, nodeId), k -> buildMachine(nodeId, defaultInitial));
    }

    private OrcStateMachine<NodeLifecycleState> buildMachine(NodeId nodeId, NodeLifecycleState initialState) {
        var builder = DefaultOrcStateMachine.builder(
            "node-" + nodeId.value(), NodeLifecycleState.class, initialState);

        for (var t : lifecycle.transitions()) {
            builder.transition(t.from(), t.to());
        }

        return builder.build();
    }

    private boolean tryTransition(OrcStateMachine<NodeLifecycleState> machine,
                                   NodeLifecycleState from, NodeLifecycleState to,
                                   NodeId nodeId, String tenancyId) {
        try {
            boolean success = machine.transition(from, to);
            if (success) {
                fireExitActions(nodeId, from, tenancyId);
                fireEnterActions(nodeId, to, tenancyId);
            }
            return success;
        } catch (IllegalTransitionException e) {
            LOG.log(Level.WARNING, "Illegal lifecycle transition for node {0}: {1}",
                new Object[]{nodeId.value(), e.getMessage()});
            return false;
        }
    }

    private void tryTransitionQuietly(OrcStateMachine<NodeLifecycleState> machine,
                                       NodeLifecycleState from, NodeLifecycleState to,
                                       NodeId nodeId, String tenancyId) {
        try {
            boolean success = machine.transition(from, to);
            if (success) {
                fireExitActions(nodeId, from, tenancyId);
                fireEnterActions(nodeId, to, tenancyId);
            }
        } catch (IllegalTransitionException e) {
            LOG.log(Level.FINE, "Recovery transition not defined for node {0}: {1} → {2}",
                new Object[]{nodeId.value(), from, to});
        }
    }

    private void fireEnterActions(NodeId nodeId, NodeLifecycleState state, String tenancyId) {
        List<TransitionAction> actions = lifecycle.onEnter().get(state);
        if (actions == null) return;
        for (TransitionAction action : actions) {
            try {
                actionHandler.execute(action, nodeId, state, tenancyId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "onEnter action failed for node {0} state {1}: {2}",
                    new Object[]{nodeId.value(), state, e.getMessage()});
            }
        }
    }

    private void fireExitActions(NodeId nodeId, NodeLifecycleState state, String tenancyId) {
        List<TransitionAction> actions = lifecycle.onExit().get(state);
        if (actions == null) return;
        for (TransitionAction action : actions) {
            try {
                actionHandler.execute(action, nodeId, state, tenancyId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "onExit action failed for node {0} state {1}: {2}",
                    new Object[]{nodeId.value(), state, e.getMessage()});
            }
        }
    }
}
