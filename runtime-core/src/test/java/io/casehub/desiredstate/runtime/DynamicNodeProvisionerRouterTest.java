package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicNodeProvisionerRouterTest {

    private static final NodeType TEST_TYPE = NodeType.of("test-type");

    @Test
    void registerAndRoute() {
        var router      = new DynamicNodeProvisionerRouter();
        var provisioner = new StubProvisioner(Set.of(TEST_TYPE));
        router.register(provisioner);

        var node   = new DesiredNode(NodeId.of("n1"), new TestSpec(TEST_TYPE), HumanGating.NONE);
        var result = router.provision(node, new ProvisionContext("tenant-1", new DefaultDesiredStateGraphFactory().empty(), null));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void unregisterRemovesRouting() {
        var router      = new DynamicNodeProvisionerRouter();
        var provisioner = new StubProvisioner(Set.of(TEST_TYPE));
        router.register(provisioner);
        router.unregister(TEST_TYPE);

        var node   = new DesiredNode(NodeId.of("n1"), new TestSpec(TEST_TYPE), HumanGating.NONE);
        var result = router.provision(node, new ProvisionContext("tenant-1", new DefaultDesiredStateGraphFactory().empty(), null));
        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void registerDuplicateTypeThrows() {
        var router = new DynamicNodeProvisionerRouter();
        var p1     = new StubProvisioner(Set.of(TEST_TYPE));
        var p2     = new StubProvisioner(Set.of(TEST_TYPE));
        router.register(p1);
        assertThatThrownBy(() -> router.register(p2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Type collision");
    }

    @Test
    void allHandledTypesReflectsRegistrations() {
        var router = new DynamicNodeProvisionerRouter();
        assertThat(router.allHandledTypes()).isEmpty();

        var typeA = NodeType.of("a");
        var typeB = NodeType.of("b");
        router.register(new StubProvisioner(Set.of(typeA)));
        router.register(new StubProvisioner(Set.of(typeB)));
        assertThat(router.allHandledTypes()).containsExactlyInAnyOrder(typeA, typeB);

        router.unregister(typeA);
        assertThat(router.allHandledTypes()).containsExactly(typeB);
    }

    @Test
    void resyncIntervalFromProvisioner() {
        var router = new DynamicNodeProvisionerRouter();
        router.register(new StubProvisioner(Set.of(TEST_TYPE)));
        assertThat(router.resyncIntervalFor(TEST_TYPE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void resyncIntervalDefaultsForUnknownType() {
        var router = new DynamicNodeProvisionerRouter();
        assertThat(router.resyncIntervalFor(NodeType.of("unknown"))).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void deprovisionRoutes() {
        var router = new DynamicNodeProvisionerRouter();
        router.register(new StubProvisioner(Set.of(TEST_TYPE)));

        var node   = new DesiredNode(NodeId.of("n1"), new TestSpec(TEST_TYPE), HumanGating.NONE);
        var result = router.deprovision(node, new io.casehub.desiredstate.api.DeprovisionContext("tenant-1", new DefaultDesiredStateGraphFactory().empty(), null));
        assertThat(result).isInstanceOf(io.casehub.desiredstate.api.DeprovisionResult.Success.class);
    }

    @Test
    void deprovisionFailsForUnknownType() {
        var router = new DynamicNodeProvisionerRouter();
        var node   = new DesiredNode(NodeId.of("n1"), new TestSpec(TEST_TYPE), HumanGating.NONE);
        var result = router.deprovision(node, new io.casehub.desiredstate.api.DeprovisionContext("tenant-1", new DefaultDesiredStateGraphFactory().empty(), null));
        assertThat(result).isInstanceOf(io.casehub.desiredstate.api.DeprovisionResult.Failed.class);
    }

    @Test
    void multiTypeCollisionDoesNotLeavePartialRegistration() {
        var router = new DynamicNodeProvisionerRouter();
        var typeA  = NodeType.of("a");
        var typeB  = NodeType.of("b");
        router.register(new StubProvisioner(Set.of(typeB)));
        assertThatThrownBy(() -> router.register(new StubProvisioner(Set.of(typeA, typeB))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(router.allHandledTypes()).containsExactly(typeB);
    }


    private record TestSpec(NodeType nodeType) implements NodeSpec {}

    private static class StubProvisioner implements NodeProvisioner {
        private final Set<NodeType> types;

        StubProvisioner(Set<NodeType> types) {
            this.types = types;
        }

        @Override
        public Set<NodeType> handledTypes() {
            return types;
        }

        @Override
        public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
            return new ProvisionResult.Success();
        }

        @Override
        public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
            return new DeprovisionResult.Success();
        }
    }
}
