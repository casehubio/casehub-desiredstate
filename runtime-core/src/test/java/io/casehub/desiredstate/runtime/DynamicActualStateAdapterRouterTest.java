package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicActualStateAdapterRouterTest {

    private static final NodeType TYPE_A = NodeType.of("type-a");
    private static final NodeType TYPE_B = NodeType.of("type-b");

    @Test
    void registerAndReadActual() {
        var router = new DynamicActualStateAdapterRouter();
        router.register(new StubAdapter(Set.of(TYPE_A), NodeStatus.PRESENT));

        var graph = new DefaultDesiredStateGraphFactory().empty()
            .withNode(new DesiredNode(NodeId.of("n1"), new TestSpec(TYPE_A), HumanGating.NONE));

        ActualState actual = router.readActual(graph, "tenant-1");
        assertThat(actual.statuses()).containsEntry(NodeId.of("n1"), NodeStatus.PRESENT);
    }

    @Test
    void unregisteredTypeReturnsUnknown() {
        var router = new DynamicActualStateAdapterRouter();
        var graph = new DefaultDesiredStateGraphFactory().empty()
            .withNode(new DesiredNode(NodeId.of("n1"), new TestSpec(TYPE_A), HumanGating.NONE));

        ActualState actual = router.readActual(graph, "tenant-1");
        assertThat(actual.statuses()).containsEntry(NodeId.of("n1"), NodeStatus.UNKNOWN);
    }

    @Test
    void unregisterRemovesAdapter() {
        var router = new DynamicActualStateAdapterRouter();
        router.register(new StubAdapter(Set.of(TYPE_A), NodeStatus.PRESENT));
        router.unregister(TYPE_A);
        assertThat(router.allHandledTypes()).isEmpty();
    }

    @Test
    void registerDuplicateTypeThrows() {
        var router = new DynamicActualStateAdapterRouter();
        router.register(new StubAdapter(Set.of(TYPE_A), NodeStatus.PRESENT));
        assertThatThrownBy(() -> router.register(new StubAdapter(Set.of(TYPE_A), NodeStatus.ABSENT)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Type collision");
    }

    @Test
    void allHandledTypesReflectsRegistrations() {
        var router = new DynamicActualStateAdapterRouter();
        router.register(new StubAdapter(Set.of(TYPE_A), NodeStatus.PRESENT));
        router.register(new StubAdapter(Set.of(TYPE_B), NodeStatus.ABSENT));
        assertThat(router.allHandledTypes()).containsExactlyInAnyOrder(TYPE_A, TYPE_B);
    }

    private record TestSpec(NodeType nodeType) implements NodeSpec {}

    private static class StubAdapter implements ActualStateAdapter {
        private final Set<NodeType> types;
        private final NodeStatus status;

        StubAdapter(Set<NodeType> types, NodeStatus status) {
            this.types = types;
            this.status = status;
        }

        @Override
        public Set<NodeType> handledTypes() {
            return types;
        }

        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            Map<NodeId, NodeStatus> result = new java.util.HashMap<>();
            for (DesiredNode node : desired.nodes().values()) {
                if (types.contains(node.type())) {
                    result.put(node.id(), status);
                }
            }
            return new ActualState(result);
        }
    }
}
