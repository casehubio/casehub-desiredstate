package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultPolicyEngineTest {

    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void noPolicies_returnsEmptyMutations() {
        FaultPolicyEngine engine = new FaultPolicyEngine(List.of());

        DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation<DesiredNode>> mutations = engine.evaluate("tenant-1", event, graph, new ActualState(Map.of()));

        assertTrue(mutations.isEmpty());
    }

    @Test
    void singlePolicy_returnsMutations() {
        FaultPolicy policy = (tid, event, current, actual) -> List.of(
            new GraphMutation.RemoveNode<>(event.node().value())
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy));

        DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation<DesiredNode>> mutations = engine.evaluate("tenant-1", event, graph, new ActualState(Map.of()));

        assertEquals(1, mutations.size());
        assertTrue(mutations.get(0) instanceof GraphMutation.RemoveNode);
        assertEquals("n1", ((GraphMutation.RemoveNode<?>) mutations.get(0)).id());
    }

    @Test
    void multiplePolicies_mergesMutations() {
        FaultPolicy policy1 = (tid, event, current, actual) -> List.of(
            new GraphMutation.RemoveNode<>("n1")
        );

        FaultPolicy policy2 = (tid, event, current, actual) -> List.of(
            new GraphMutation.UpdateNode<>("n2", new DesiredNode(NodeId.of("n2"), new TestSpec("updated"), HumanGating.NONE))
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node1 = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredNode node2 = new DesiredNode(NodeId.of("n2"), new TestSpec("N2"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node1, node2), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation<DesiredNode>> mutations = engine.evaluate("tenant-1", event, graph, new ActualState(Map.of()));

        assertEquals(2, mutations.size());
    }

    @Test
    void sameMutationFromTwoPolicies_deduplicated() {
        FaultPolicy policy1 = (tid, event, current, actual) -> List.of(
            new GraphMutation.RemoveNode<>("n1")
        );

        FaultPolicy policy2 = (tid, event, current, actual) -> List.of(
            new GraphMutation.RemoveNode<>("n1")
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation<DesiredNode>> mutations = engine.evaluate("tenant-1", event, graph, new ActualState(Map.of()));

        assertEquals(1, mutations.size());
    }

    @Test
    void conflictingMutations_throwsException() {
        FaultPolicy policy1 = (tid, event, current, actual) -> List.of(
            new GraphMutation.RemoveNode<>("n1")
        );

        FaultPolicy policy2 = (tid, event, current, actual) -> List.of(
            new GraphMutation.UpdateNode<>("n1", new DesiredNode(NodeId.of("n1"), new TestSpec("updated"), HumanGating.NONE))
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        ConflictingMutationException ex = assertThrows(
            ConflictingMutationException.class,
            () -> engine.evaluate("tenant-1", event, graph, new ActualState(Map.of()))
        );

        assertEquals("n1", ex.getNodeId());
    }

    @Test
    void dependencyMutations_noConflict() {
        FaultPolicy policy1 = (tid, event, current, actual) -> List.of(
            new GraphMutation.AddEdge<>("n1", "n2")
        );

        FaultPolicy policy2 = (tid, event, current, actual) -> List.of(
            new GraphMutation.AddEdge<>("n1", "n3")
        );

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy1, policy2));

        DesiredNode node1 = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node1), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.DEPENDENCY_UNAVAILABLE, "detail");

        List<GraphMutation<DesiredNode>> mutations = engine.evaluate("tenant-1", event, graph, new ActualState(Map.of()));

        assertEquals(2, mutations.size());
        assertTrue(mutations.stream().allMatch(m -> m instanceof GraphMutation.AddEdge));
    }

    @Test
    void policyReceivesActualState() {
        ActualState actual = new ActualState(Map.of(
            NodeId.of("n1"), NodeStatus.ABSENT,
            NodeId.of("n2"), NodeStatus.PRESENT
        ));

        FaultPolicy policy = (tid, event, current, actualState) -> {
            // Policy can now inspect actual state
            if (actualState.statusOf(event.node()).orElse(NodeStatus.UNKNOWN) == NodeStatus.ABSENT) {
                return List.of(new GraphMutation.RemoveNode<>(event.node().value()));
            }
            return List.of();
        };

        FaultPolicyEngine engine = new FaultPolicyEngine(List.of(policy));

        DesiredNode node1 = new DesiredNode(NodeId.of("n1"), new TestSpec("N1"), HumanGating.NONE);
        DesiredNode node2 = new DesiredNode(NodeId.of("n2"), new TestSpec("N2"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node1, node2), List.of());

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DESTROYED, "detail");

        List<GraphMutation<DesiredNode>> mutations = engine.evaluate("tenant-1", event, graph, actual);

        assertEquals(1, mutations.size());
        assertInstanceOf(GraphMutation.RemoveNode.class, mutations.get(0));
    }

    // Helper test spec
    record TestSpec(String value) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }
}
