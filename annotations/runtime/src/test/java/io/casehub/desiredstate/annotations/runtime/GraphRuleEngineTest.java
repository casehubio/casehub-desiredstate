package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRuleEngineTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final GraphRuleEngine engine = new GraphRuleEngine();

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    private ResolvedGraphRule imperativeRule(String methodName) {
        try {
            Method m = GraphRuleEngineTest.class.getDeclaredMethod(methodName, DesiredStateGraph.class);
            return new ResolvedGraphRule(methodName, m, null, true, List.of());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Imperative rule method implementations ---

    static List<GraphMutation> addMonitorRule(DesiredStateGraph graph) {
        if (graph.nodes().containsKey(NodeId.of("monitor"))) return List.of();
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("monitor"), new Spec("monitor", "monitor"), HumanGating.NONE)));
    }

    @Test
    void imperativeRuleAddsNode() {
        var graph = factory.of(
                List.of(new DesiredNode(NodeId.of("sink"), new Spec("sink", "sink"), HumanGating.NONE)),
                List.of());
        var result = engine.evaluate(graph, List.of(imperativeRule("addMonitorRule")));
        assertThat(result.nodes()).containsKey(NodeId.of("monitor"));
        assertThat(result.nodes()).hasSize(2);
    }

    @Test
    void emptyRuleListReturnsGraphUnchanged() {
        var graph = factory.of(
                List.of(new DesiredNode(NodeId.of("a"), new Spec("a", "a"), HumanGating.NONE)),
                List.of());
        var result = engine.evaluate(graph, List.of());
        assertThat(result.nodes()).hasSize(1);
    }

    // --- Non-convergence ---

    static List<GraphMutation> alwaysMutateRule(DesiredStateGraph graph) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("x-" + graph.version()), new Spec("x", "x"), HumanGating.NONE)));
    }

    @Test
    void nonConvergenceThrowsException() {
        var graph = factory.of(List.of(), List.of());
        assertThatThrownBy(() -> engine.evaluate(graph, List.of(imperativeRule("alwaysMutateRule"))))
                .isInstanceOf(GraphRuleNonConvergenceException.class)
                .hasMessageContaining("alwaysMutateRule")
                .hasMessageContaining("100");
    }

    // --- Conflict detection: same NodeId, different specs ---

    static List<GraphMutation> addNodeA(DesiredStateGraph graph) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("dup"), new Spec("a", "a"), HumanGating.NONE)));
    }

    static List<GraphMutation> addNodeADifferent(DesiredStateGraph graph) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("dup"), new Spec("b", "b"), HumanGating.NONE)));
    }

    @Test
    void conflictingMutationsThrowException() {
        var graph = factory.of(List.of(), List.of());
        assertThatThrownBy(() -> engine.evaluate(graph,
                List.of(imperativeRule("addNodeA"), imperativeRule("addNodeADifferent"))))
                .isInstanceOf(ConflictingMutationException.class)
                .hasMessageContaining("dup");
    }

    // --- Deduplication: identical mutations from different rules ---

    static List<GraphMutation> duplicateMutationRule(DesiredStateGraph graph) {
        if (graph.nodes().containsKey(NodeId.of("d"))) return List.of();
        var node = new DesiredNode(NodeId.of("d"), new Spec("d", "d"), HumanGating.NONE);
        return List.of(new GraphMutation.AddNode(node));
    }

    static List<GraphMutation> duplicateMutationRule2(DesiredStateGraph graph) {
        if (graph.nodes().containsKey(NodeId.of("d"))) return List.of();
        var node = new DesiredNode(NodeId.of("d"), new Spec("d", "d"), HumanGating.NONE);
        return List.of(new GraphMutation.AddNode(node));
    }

    @Test
    void identicalDuplicateMutationsDeduplicated() {
        var graph = factory.of(List.of(), List.of());
        var result = engine.evaluate(graph,
                List.of(imperativeRule("duplicateMutationRule"), imperativeRule("duplicateMutationRule2")));
        assertThat(result.nodes()).containsKey(NodeId.of("d"));
    }

    // --- Cycle detection ---

    static List<GraphMutation> createCycleRule(DesiredStateGraph graph) {
        return List.of(new GraphMutation.AddDependency(new Dependency(NodeId.of("b"), NodeId.of("a"))));
    }

    @Test
    void cycleIntroducedByRuleThrowsException() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("a"), new Spec("a", "a"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("b"), new Spec("b", "b"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("a"), NodeId.of("b"))));
        assertThatThrownBy(() -> engine.evaluate(graph, List.of(imperativeRule("createCycleRule"))))
                .isInstanceOf(GraphRuleCycleException.class);
    }

    // --- Mutation ordering: RemoveNode before AddDependency prevents false-positive cycles ---

    static List<GraphMutation> addEdgeAndRemoveNode(DesiredStateGraph graph) {
        if (!graph.nodes().containsKey(NodeId.of("b"))) return List.of();
        return List.of(
                new GraphMutation.RemoveNode(NodeId.of("b")),
                new GraphMutation.AddDependency(new Dependency(NodeId.of("c"), NodeId.of("a"))));
    }

    @Test
    void removeNodeBeforeAddDependencyNoFalsePositiveCycle() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("a"), new Spec("a", "a"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("b"), new Spec("b", "b"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("c"), new Spec("c", "c"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("a"), NodeId.of("b")),
                        new Dependency(NodeId.of("b"), NodeId.of("c"))));
        var result = engine.evaluate(graph, List.of(imperativeRule("addEdgeAndRemoveNode")));
        assertThat(result.nodes()).doesNotContainKey(NodeId.of("b"));
        assertThat(result.dependencies()).contains(new Dependency(NodeId.of("c"), NodeId.of("a")));
    }

    // --- Contradictory edge mutations ---

    static List<GraphMutation> contradictoryEdgeRule1(DesiredStateGraph graph) {
        return List.of(new GraphMutation.AddDependency(new Dependency(NodeId.of("a"), NodeId.of("b"))));
    }

    static List<GraphMutation> contradictoryEdgeRule2(DesiredStateGraph graph) {
        return List.of(new GraphMutation.RemoveDependency(new Dependency(NodeId.of("a"), NodeId.of("b"))));
    }

    @Test
    void contradictoryEdgeMutationsThrowConflict() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("a"), new Spec("a", "a"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("b"), new Spec("b", "b"), HumanGating.NONE)),
                List.of());
        assertThatThrownBy(() -> engine.evaluate(graph,
                List.of(imperativeRule("contradictoryEdgeRule1"), imperativeRule("contradictoryEdgeRule2"))))
                .isInstanceOf(ConflictingMutationException.class);
    }
}
