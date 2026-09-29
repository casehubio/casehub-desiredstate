package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeLifecycleDefinition;
import io.casehub.desiredstate.api.NodeLifecycleDefinition.Transition;
import io.casehub.desiredstate.api.NodeLifecycleState;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.casehub.desiredstate.api.NodeLifecycleState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StatefulNodeProvisionerRouterTest {

    static final NodeType TYPE_MANAGED = NodeType.of("managed");
    static final NodeType TYPE_SIMPLE = NodeType.of("simple");
    static final DesiredStateGraph dummyGraph = new DefaultDesiredStateGraphFactory().empty();

    private MockNodeProvisioner mockProvisioner(Set<NodeType> types) {
        var prov = new MockNodeProvisioner();
        prov.setHandledTypes(types);
        return prov;
    }

    private NodeLifecycleDefinition standardLifecycle(NodeType type) {
        return new NodeLifecycleDefinition(type,
            Set.of(
                new Transition(ABSENT, PROVISIONING),
                new Transition(PROVISIONING, PRESENT),
                new Transition(PROVISIONING, DRIFTED),
                new Transition(PRESENT, DEPROVISIONING),
                new Transition(DEPROVISIONING, ABSENT)
            ), Map.of(), Map.of());
    }

    @Test
    void provisioner_withLifecycleDefinition_getsWrapped() {
        var prov = mockProvisioner(Set.of(TYPE_MANAGED));
        var lifecycle = standardLifecycle(TYPE_MANAGED);
        TransitionActionHandler handler = (a, n, s, t) -> {};

        var router = new StatefulNodeProvisionerRouter(
            List.of(prov), List.of(lifecycle), handler);

        var node = new DesiredNode(NodeId.of("n1"),
            new TestSpec(TYPE_MANAGED, "n1"), HumanGating.NONE);

        var result1 = router.provision(node, new ProvisionContext("t1", dummyGraph));
        assertInstanceOf(ProvisionResult.Success.class, result1);

        var result2 = router.provision(node, new ProvisionContext("t1", dummyGraph));
        assertInstanceOf(ProvisionResult.Failed.class, result2);
        assertThat(((ProvisionResult.Failed) result2).reason()).contains("lifecycle");
    }

    @Test
    void provisioner_withoutLifecycle_notWrapped() {
        var prov = mockProvisioner(Set.of(TYPE_SIMPLE));
        TransitionActionHandler handler = (a, n, s, t) -> {};

        var router = new StatefulNodeProvisionerRouter(
            List.of(prov), List.of(), handler);

        var node = new DesiredNode(NodeId.of("n1"),
            new TestSpec(TYPE_SIMPLE, "n1"), HumanGating.NONE);

        var result1 = router.provision(node, new ProvisionContext("t1", dummyGraph));
        assertInstanceOf(ProvisionResult.Success.class, result1);

        var result2 = router.provision(node, new ProvisionContext("t1", dummyGraph));
        assertInstanceOf(ProvisionResult.Success.class, result2);
        assertThat(prov.provisioned).hasSize(2);
    }

    @Test
    void mixedProvisioners_onlyMatchingGetsWrapped() {
        var managedProv = mockProvisioner(Set.of(TYPE_MANAGED));
        var simpleProv = mockProvisioner(Set.of(TYPE_SIMPLE));
        var lifecycle = standardLifecycle(TYPE_MANAGED);
        TransitionActionHandler handler = (a, n, s, t) -> {};

        var router = new StatefulNodeProvisionerRouter(
            List.of(managedProv, simpleProv), List.of(lifecycle), handler);

        var managedNode = new DesiredNode(NodeId.of("m1"),
            new TestSpec(TYPE_MANAGED, "m1"), HumanGating.NONE);
        var simpleNode = new DesiredNode(NodeId.of("s1"),
            new TestSpec(TYPE_SIMPLE, "s1"), HumanGating.NONE);

        router.provision(managedNode, new ProvisionContext("t1", dummyGraph));
        var managedAgain = router.provision(managedNode, new ProvisionContext("t1", dummyGraph));
        assertInstanceOf(ProvisionResult.Failed.class, managedAgain);

        router.provision(simpleNode, new ProvisionContext("t1", dummyGraph));
        var simpleAgain = router.provision(simpleNode, new ProvisionContext("t1", dummyGraph));
        assertInstanceOf(ProvisionResult.Success.class, simpleAgain);
    }

    record TestSpec(NodeType nodeType, String id) implements NodeSpec {}
}
