package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeLifecycleDefinition;
import io.casehub.desiredstate.api.NodeLifecycleDefinition.Transition;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;
import io.casehub.desiredstate.api.TransitionAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.casehub.desiredstate.api.NodeLifecycleState.ABSENT;
import static io.casehub.desiredstate.api.NodeLifecycleState.DEPROVISIONING;
import static io.casehub.desiredstate.api.NodeLifecycleState.DRIFTED;
import static io.casehub.desiredstate.api.NodeLifecycleState.PRESENT;
import static io.casehub.desiredstate.api.NodeLifecycleState.PROVISIONING;
import static io.casehub.desiredstate.api.NodeLifecycleState.RESUMING;
import static io.casehub.desiredstate.api.NodeLifecycleState.SUSPENDED;
import static io.casehub.desiredstate.api.NodeLifecycleState.SUSPENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatefulNodeProvisionerTest {

    private static final NodeType TEST_TYPE = NodeType.of("test");
    private static final NodeSpec TEST_SPEC = new NodeSpec() {
        @Override
        public NodeType nodeType() { return TEST_TYPE; }
    };

    private NodeProvisioner delegate;
    private DesiredStateGraph graph;
    private List<TransitionAction> emittedActions;
    private TransitionActionHandler actionHandler;

    @BeforeEach
    void setUp() {
        delegate = mock(NodeProvisioner.class);
        when(delegate.handledTypes()).thenReturn(Set.of(TEST_TYPE));
        graph = mock(DesiredStateGraph.class);
        emittedActions = Collections.synchronizedList(new ArrayList<>());
        actionHandler = (action, nodeId, state, tenancyId) -> emittedActions.add(action);
    }

    private NodeLifecycleDefinition standardLifecycle() {
        return new NodeLifecycleDefinition(TEST_TYPE,
            Set.of(
                new Transition(ABSENT, PROVISIONING),
                new Transition(PROVISIONING, PRESENT),
                new Transition(PROVISIONING, DRIFTED),
                new Transition(PRESENT, DEPROVISIONING),
                new Transition(DEPROVISIONING, ABSENT)
            ), Map.of(), Map.of());
    }

    private NodeLifecycleDefinition fullLifecycle() {
        return new NodeLifecycleDefinition(TEST_TYPE,
            Set.of(
                new Transition(ABSENT, PROVISIONING),
                new Transition(PROVISIONING, PRESENT),
                new Transition(PROVISIONING, DRIFTED),
                new Transition(PRESENT, DEPROVISIONING),
                new Transition(DEPROVISIONING, ABSENT),
                new Transition(PRESENT, SUSPENDING),
                new Transition(SUSPENDING, SUSPENDED),
                new Transition(SUSPENDED, RESUMING),
                new Transition(RESUMING, PRESENT)
            ), Map.of(), Map.of());
    }

    @Test
    void provision_validTransition_delegatesAndTransitions() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.Success());

        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
        var result = provisioner.provision(node, new ProvisionContext("t1", graph));

        assertInstanceOf(ProvisionResult.Success.class, result);
        verify(delegate).provision(any(), any());
    }

    @Test
    void provision_alreadyPresent_returnsFailed() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.Success());

        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);

        provisioner.provision(node, new ProvisionContext("t1", graph));
        var secondResult = provisioner.provision(node, new ProvisionContext("t1", graph));

        assertInstanceOf(ProvisionResult.Failed.class, secondResult);
        assertThat(((ProvisionResult.Failed) secondResult).reason()).contains("lifecycle");
    }

    @Test
    void provision_failure_transitionsToDrifted() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.Failed("timeout"));

        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
        var result = provisioner.provision(node, new ProvisionContext("t1", graph));

        assertInstanceOf(ProvisionResult.Failed.class, result);
    }

    @Test
    void provision_alreadyConverged_transitionsToPresentState() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.AlreadyConverged());

        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        var node        = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
        var result      = provisioner.provision(node, new ProvisionContext("t1", graph));

        assertInstanceOf(ProvisionResult.AlreadyConverged.class, result);

        // After AlreadyConverged, should be in PRESENT state — deprovision should work
        when(delegate.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());
        var deprovisionResult = provisioner.deprovision(node, new DeprovisionContext("t1", graph));
        assertInstanceOf(DeprovisionResult.Success.class, deprovisionResult);
    }


    @Test
    void deprovision_validTransition_delegatesAndTransitions() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.Success());
        when(delegate.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());

        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);

        provisioner.provision(node, new ProvisionContext("t1", graph));
        var result = provisioner.deprovision(node, new DeprovisionContext("t1", graph));

        assertInstanceOf(DeprovisionResult.Success.class, result);
    }

    @Test
    void stateReconstruction_derivesFromInitialState() {
        when(delegate.deprovision(any(), any())).thenReturn(new DeprovisionResult.Success());

        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);

        provisioner.initializeNodeState(NodeId.of("n1"), PRESENT, "t1");
        var result = provisioner.deprovision(node, new DeprovisionContext("t1", graph));

        assertInstanceOf(DeprovisionResult.Success.class, result);
    }

    @Test
    void onEnterAction_emitsEvent() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.Success());

        var lifecycle = new NodeLifecycleDefinition(TEST_TYPE,
            Set.of(
                new Transition(ABSENT, PROVISIONING),
                new Transition(PROVISIONING, PRESENT),
                new Transition(PROVISIONING, DRIFTED),
                new Transition(PRESENT, DEPROVISIONING),
                new Transition(DEPROVISIONING, ABSENT)
            ),
            Map.of(PRESENT, List.of(new TransitionAction.EmitEvent("node.ready"))),
            Map.of());

        var provisioner = new StatefulNodeProvisioner(delegate, lifecycle, actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
        provisioner.provision(node, new ProvisionContext("t1", graph));

        assertThat(emittedActions).hasSize(1);
        assertThat(emittedActions.get(0)).isInstanceOf(TransitionAction.EmitEvent.class);
        assertThat(((TransitionAction.EmitEvent) emittedActions.get(0)).eventType()).isEqualTo("node.ready");
    }

    @Test
    void suspend_resume_fullLifecycle() {
        when(delegate.provision(any(), any())).thenReturn(new ProvisionResult.Success());
        when(delegate.suspend(any(), any())).thenReturn(new SuspendResult.Success());
        when(delegate.resume(any(), any())).thenReturn(new ResumeResult.Success());

        var provisioner = new StatefulNodeProvisioner(delegate, fullLifecycle(), actionHandler);
        var node = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);

        provisioner.provision(node, new ProvisionContext("t1", graph));
        var suspendResult = provisioner.suspend(node, new SuspendContext("t1", graph));
        assertInstanceOf(SuspendResult.Success.class, suspendResult);

        var resumeResult = provisioner.resume(node, new ResumeContext("t1", graph));
        assertInstanceOf(ResumeResult.Success.class, resumeResult);
    }

    @Test
    void handledTypes_forwardedToDelegate() {
        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        assertThat(provisioner.handledTypes()).isEqualTo(Set.of(TEST_TYPE));
    }

    @Test
    void supportsStatefulLifecycle_basedOnLifecycleDefinition() {
        var provisioner = new StatefulNodeProvisioner(delegate, standardLifecycle(), actionHandler);
        assertThat(provisioner.supportsStatefulLifecycle()).isFalse();

        var fullProvisioner = new StatefulNodeProvisioner(delegate, fullLifecycle(), actionHandler);
        assertThat(fullProvisioner.supportsStatefulLifecycle()).isTrue();
    }
}
