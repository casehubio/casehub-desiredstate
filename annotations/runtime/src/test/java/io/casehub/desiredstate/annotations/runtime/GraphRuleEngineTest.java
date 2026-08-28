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
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRuleEngineTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final GraphRuleEngine engine = new GraphRuleEngine();

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    private ResolvedRule imperativeRule(String methodName) {
        try {
            Method m = GraphRuleEngineTest.class.getDeclaredMethod(methodName, DesiredStateGraph.class);
            return new ResolvedRule.ImperativeRule(methodName, m, null);
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

    // --- Parameterized rule helpers ---

    private ResolvedRule parameterizedRule(String methodName,
            List<PatternParameterDescriptor> patterns) {
        try {
            Class<?>[] paramTypes = new Class<?>[patterns.size()];
            for (int i = 0; i < patterns.size(); i++) {
                paramTypes[i] = patterns.get(i).kind() == PatternKind.NOT_EXISTS
                        ? Void.class : DesiredNode.class;
            }
            Method m = GraphRuleEngineTest.class.getDeclaredMethod(methodName, paramTypes);
            return new ResolvedRule.ParameterizedReflectiveRule(methodName, m, null, patterns);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Parameterized rule method implementations ---

    static List<GraphMutation> addValidatorForTransformer(DesiredNode transformer) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("validator-" + transformer.id().value()),
                        new Spec("validator", "validator"), HumanGating.NONE)));
    }

    @Test
    void matchBindsNodesByType() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx1"), new Spec("tx1", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("tx2"), new Spec("tx2", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("src"), new Spec("src", "source"), HumanGating.NONE)),
                List.of());
        var rule = parameterizedRule("addValidatorForTransformer", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("validator-tx1"));
        assertThat(result.nodes()).containsKey(NodeId.of("validator-tx2"));
        assertThat(result.nodes()).doesNotContainKey(NodeId.of("validator-src"));
    }

    static List<GraphMutation> bindDirectDep(DesiredNode matched, DesiredNode dep) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("found-" + dep.id().value()),
                        new Spec("found", "found"), HumanGating.NONE)));
    }

    @Test
    void directDepBindsDirectDependency() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("src"), new Spec("src", "source"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("tx"), NodeId.of("src"))));
        var rule = parameterizedRule("bindDirectDep", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "source", "", Direction.DEPENDENCIES)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("found-src"));
    }

    @Test
    void directDepDependentsDirection() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("src"), new Spec("src", "source"), HumanGating.NONE),
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("tx"), NodeId.of("src"))));
        var rule = parameterizedRule("bindDirectDep", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "source", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "transformer", "", Direction.DEPENDENTS)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("found-tx"));
    }

    static List<GraphMutation> bindReachable(DesiredNode matched, DesiredNode reached) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("reached-" + reached.id().value()),
                        new Spec("reached", "reached"), HumanGating.NONE)));
    }

    @Test
    void reachesFindsTransitiveNode() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("mid"), new Spec("mid", "middle"), HumanGating.NONE),
                new DesiredNode(NodeId.of("src"), new Spec("src", "source"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("tx"), NodeId.of("mid")),
                        new Dependency(NodeId.of("mid"), NodeId.of("src"))));
        var rule = parameterizedRule("bindReachable", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.REACHES, "source", "", Direction.DEPENDENCIES)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("reached-src"));
    }

    static List<GraphMutation> guardedRule(DesiredNode transformer, Void guard) {
        return List.of(new GraphMutation.AddNode(
                new DesiredNode(NodeId.of("validator-" + transformer.id().value()),
                        new Spec("validator", "validator"), HumanGating.NONE)));
    }

    @Test
    void notExistsGlobalGuardPreventsRule() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("v"), new Spec("v", "validator"), HumanGating.NONE)),
                List.of());
        var rule = parameterizedRule("guardedRule", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "", Direction.DEPENDENCIES)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).hasSize(2);
    }

    @Test
    void notExistsGlobalGuardAllowsRuleWhenAbsent() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE)),
                List.of());
        var rule = parameterizedRule("guardedRule", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "", Direction.DEPENDENCIES)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("validator-tx"));
    }

    @Test
    void notExistsRelationalGuardChecksNamedBinding() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("v"), new Spec("v", "validator"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("v"), NodeId.of("tx"))));
        var rule = parameterizedRule("guardedRule", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "transformer", Direction.DEPENDENTS)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).hasSize(2);
    }

    @Test
    void notExistsRelationalGuardAllowsWhenNoRelation() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                new DesiredNode(NodeId.of("v"), new Spec("v", "validator"), HumanGating.NONE)),
                List.of());
        var rule = parameterizedRule("guardedRule", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "transformer", Direction.DEPENDENTS)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("validator-tx"));
    }

    @Test
    void fixedPointConvergenceWithGuard() {
        var graph = factory.of(List.of(
                new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE)),
                List.of());
        var rule = parameterizedRule("guardedRule", List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "", Direction.DEPENDENCIES)));
        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).containsKey(NodeId.of("validator-tx"));
        assertThat(result.nodes()).hasSize(2);
    }

    // --- Declarative rule tests ---

    @Test
    void declarativeRuleAddsNodeViaActionEvaluator() {
        var graph = factory.of(List.of(
                                       new DesiredNode(NodeId.of("sink-1"), new Spec("sink-1", "sink"), HumanGating.NONE)),
                               List.of());

        Function<Map<String, DesiredNode>, List<GraphMutation>> evaluator = bindings -> {
            DesiredNode sink = bindings.get("sink");
            DesiredNode monitor = new DesiredNode(
                    NodeId.of("monitor-" + sink.id().value()),
                    new Spec("monitor-" + sink.id().value(), "monitor"), HumanGating.NONE);
            return List.of(
                    new GraphMutation.AddNode(monitor),
                    new GraphMutation.AddDependency(new Dependency(monitor.id(), sink.id())));
        };

        var rule = new ResolvedRule.DeclarativeRule("ensure-monitoring",
                                                    List.of(
                                                            new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                                                            new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "monitor", "sink", Direction.DEPENDENTS)),
                                                    new String[]{"sink", "guard"},
                                                    evaluator);

        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes()).containsKey(NodeId.of("monitor-sink-1"));
        assertThat(result.dependencies()).contains(
                new Dependency(NodeId.of("monitor-sink-1"), NodeId.of("sink-1")));
    }

    @Test
    void declarativeRuleConvergesWithGuard() {
        var graph = factory.of(List.of(
                                       new DesiredNode(NodeId.of("sink-1"), new Spec("sink-1", "sink"), HumanGating.NONE),
                                       new DesiredNode(NodeId.of("sink-2"), new Spec("sink-2", "sink"), HumanGating.NONE)),
                               List.of());

        Function<Map<String, DesiredNode>, List<GraphMutation>> evaluator = bindings -> {
            DesiredNode sink = bindings.get("sink");
            DesiredNode monitor = new DesiredNode(
                    NodeId.of("monitor-" + sink.id().value()),
                    new Spec("monitor", "monitor"), HumanGating.NONE);
            return List.of(
                    new GraphMutation.AddNode(monitor),
                    new GraphMutation.AddDependency(new Dependency(monitor.id(), sink.id())));
        };

        var rule = new ResolvedRule.DeclarativeRule("ensure-monitoring",
                                                    List.of(
                                                            new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                                                            new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "monitor", "sink", Direction.DEPENDENTS)),
                                                    new String[]{"sink", "guard"},
                                                    evaluator);

        var result = engine.evaluate(graph, List.of(rule));
        assertThat(result.nodes()).hasSize(4);
        assertThat(result.nodes()).containsKey(NodeId.of("monitor-sink-1"));
        assertThat(result.nodes()).containsKey(NodeId.of("monitor-sink-2"));
    }


}
