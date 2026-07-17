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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultActualStateAdapterRouterTest {

    record TestSpec(String name) implements NodeSpec {}

    static final NodeType TYPE_A = NodeType.of("type-a");
    static final NodeType TYPE_B = NodeType.of("type-b");

    @Test void routesToCorrectAdapterByNodeType() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };
        ActualStateAdapter adapterB = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.ABSENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA, adapterB));
        var nodeA = new DesiredNode(NodeId.of("a"), TYPE_A, new TestSpec("x"), HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("b"), TYPE_B, new TestSpec("x"), HumanGating.NONE);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeB);

        ActualState result = router.readActual(graph, "tenant-1");

        assertThat(result.statuses()).containsEntry(NodeId.of("a"), NodeStatus.PRESENT);
        assertThat(result.statuses()).containsEntry(NodeId.of("b"), NodeStatus.ABSENT);
    }

    @Test void rejectsOverlappingTypes() {
        ActualStateAdapter adapter1 = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };
        ActualStateAdapter adapter2 = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };

        assertThatThrownBy(() -> new DefaultActualStateAdapterRouter(List.of(adapter1, adapter2)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type-a");
    }

    @Test void uncoveredNodesGetUnknownStatus() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) {
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                d.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA));
        var nodeA = new DesiredNode(NodeId.of("a"), TYPE_A, new TestSpec("x"), HumanGating.NONE);
        var nodeUnknown = new DesiredNode(NodeId.of("u"), NodeType.of("unregistered"), new TestSpec("x"), HumanGating.NONE);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeUnknown);

        ActualState result = router.readActual(graph, "tenant-1");

        assertThat(result.statuses()).containsEntry(NodeId.of("a"), NodeStatus.PRESENT);
        assertThat(result.statuses()).containsEntry(NodeId.of("u"), NodeStatus.UNKNOWN);
    }

    @Test void allHandledTypesReturnsUnionOfAdapterTypes() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };
        ActualStateAdapter adapterB = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA, adapterB));
        assertThat(router.allHandledTypes()).containsExactlyInAnyOrder(TYPE_A, TYPE_B);
    }

    @Test void emptyGraphProducesEmptyActualState() {
        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph d, String t) { return new ActualState(Map.of()); }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA));
        var graph = ImmutableDesiredStateGraph.empty();

        ActualState result = router.readActual(graph, "tenant-1");
        assertThat(result.statuses()).isEmpty();
    }

    @Test void adapterReceivesFilteredGraph() {
        var receivedNodeSets = new ArrayList<Set<NodeId>>();

        ActualStateAdapter adapterA = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A); }
            @Override public ActualState readActual(DesiredStateGraph desired, String t) {
                receivedNodeSets.add(Set.copyOf(desired.nodes().keySet()));
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };
        ActualStateAdapter adapterB = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph desired, String t) {
                receivedNodeSets.add(Set.copyOf(desired.nodes().keySet()));
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapterA, adapterB));
        var nodeA = new DesiredNode(NodeId.of("a"), TYPE_A, new TestSpec("x"), HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("b"), TYPE_B, new TestSpec("x"), HumanGating.NONE);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeB);

        router.readActual(graph, "tenant-1");

        assertThat(receivedNodeSets).hasSize(2);
        assertThat(receivedNodeSets).anySatisfy(nodes -> assertThat(nodes).containsExactly(NodeId.of("a")));
        assertThat(receivedNodeSets).anySatisfy(nodes -> assertThat(nodes).containsExactly(NodeId.of("b")));
    }

    @Test void singleAdapterReceivesFullGraph() {
        var receivedNodes = new ArrayList<Set<NodeId>>();

        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(TYPE_A, TYPE_B); }
            @Override public ActualState readActual(DesiredStateGraph desired, String t) {
                receivedNodes.add(Set.copyOf(desired.nodes().keySet()));
                Map<NodeId, NodeStatus> statuses = new HashMap<>();
                desired.nodes().keySet().forEach(id -> statuses.put(id, NodeStatus.PRESENT));
                return new ActualState(statuses);
            }
        };

        var router = new DefaultActualStateAdapterRouter(List.of(adapter));
        var nodeA = new DesiredNode(NodeId.of("a"), TYPE_A, new TestSpec("x"), HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("b"), TYPE_B, new TestSpec("x"), HumanGating.NONE);
        var graph = ImmutableDesiredStateGraph.empty().withNode(nodeA).withNode(nodeB);

        router.readActual(graph, "tenant-1");

        assertThat(receivedNodes).hasSize(1);
        assertThat(receivedNodes.getFirst()).containsExactlyInAnyOrder(NodeId.of("a"), NodeId.of("b"));
    }
}
