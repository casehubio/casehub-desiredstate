package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class DefaultNodeProvisionerRouterTest {

    static final NodeType TYPE_A = NodeType.of("type-a");
    static final NodeType TYPE_B = NodeType.of("type-b");
    static final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    static final DesiredStateGraph dummyGraph = factory.empty();

    @Test
    void routesToCorrectProvisioner() {
        var provA = mockProvisioner(Set.of(TYPE_A));
        var provB = mockProvisioner(Set.of(TYPE_B));
        var router = new DefaultNodeProvisionerRouter(List.of(provA, provB));

        var nodeA = new DesiredNode(NodeId.of("a"), TYPE_A, new TestSpec("a"), false);
        var result = router.provision(nodeA, new ProvisionContext("t1", dummyGraph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(provA.provisioned).hasSize(1);
        assertThat(provB.provisioned).isEmpty();
    }

    @Test
    void failsForUnknownNodeType() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        var router = new DefaultNodeProvisionerRouter(List.of(prov));

        var unknown = new DesiredNode(NodeId.of("x"), NodeType.of("unknown"), new TestSpec("x"), false);
        var result = router.provision(unknown, new ProvisionContext("t1", dummyGraph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("No provisioner for node type");
    }

    @Test
    void detectsConflictingNodeTypes() {
        var prov1 = mockProvisioner(Set.of(TYPE_A));
        var prov2 = mockProvisioner(Set.of(TYPE_A));

        assertThatThrownBy(() -> new DefaultNodeProvisionerRouter(List.of(prov1, prov2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type-a")
            .hasMessageContaining("claimed by both");
    }

    @Test
    void validatesResyncIntervalFloor() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        prov.setResyncInterval(Duration.ZERO);

        assertThatThrownBy(() -> new DefaultNodeProvisionerRouter(List.of(prov)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be");
    }

    @Test
    void resyncIntervalForReturnProvisionerDefault() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        prov.setResyncInterval(Duration.ofSeconds(30));
        var router = new DefaultNodeProvisionerRouter(List.of(prov));

        assertThat(router.resyncIntervalFor(TYPE_A)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void resyncIntervalForUnknownTypeReturnsFiveMinutes() {
        var prov = mockProvisioner(Set.of(TYPE_A));
        var router = new DefaultNodeProvisionerRouter(List.of(prov));

        assertThat(router.resyncIntervalFor(NodeType.of("unknown")))
            .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void allHandledTypesReturnsUnionOfProvisioners() {
        var prov1 = mockProvisioner(Set.of(TYPE_A));
        var prov2 = mockProvisioner(Set.of(TYPE_B));
        var router = new DefaultNodeProvisionerRouter(List.of(prov1, prov2));

        assertThat(router.allHandledTypes()).containsExactlyInAnyOrder(TYPE_A, TYPE_B);
    }

    @Test
    void deprovisionRoutesToCorrectProvisioner() {
        var provA = mockProvisioner(Set.of(TYPE_A));
        var provB = mockProvisioner(Set.of(TYPE_B));
        var router = new DefaultNodeProvisionerRouter(List.of(provA, provB));

        var nodeB = new DesiredNode(NodeId.of("b"), TYPE_B, new TestSpec("b"), false);
        var result = router.deprovision(nodeB, new DeprovisionContext("t1", dummyGraph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(provB.deprovisioned).hasSize(1);
        assertThat(provA.deprovisioned).isEmpty();
    }

    private MockNodeProvisioner mockProvisioner(Set<NodeType> types) {
        var mock = new MockNodeProvisioner();
        mock.setHandledTypes(types);
        return mock;
    }

    record TestSpec(String value) implements NodeSpec {}
}
