package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PatternEvaluatorTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    @Test
    void evaluate_matchSingleType_returnsAllMatchingNodes() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("sink-2"), new Spec("s2", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("db-1"), new Spec("d1", "db"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES));
        String[] bindingNames = {"sink"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(2);
        assertThat(bindings).allSatisfy(b ->
                assertThat(b.get("sink").type()).isEqualTo(NodeType.of("sink")));
    }

    @Test
    void evaluate_matchWithDirectDep_expandsChain() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("ds-1"), new Spec("d1", "data-source"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("sink-1"), NodeId.of("ds-1"))));

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "data-source", "sink", Direction.DEPENDENCIES));
        String[] bindingNames = {"sink", "upstream"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).get("sink").id()).isEqualTo(NodeId.of("sink-1"));
        assertThat(bindings.get(0).get("upstream").id()).isEqualTo(NodeId.of("ds-1"));
    }

    @Test
    void evaluate_directDep_noMatch_producesEmptyBindings() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "data-source", "sink", Direction.DEPENDENCIES));
        String[] bindingNames = {"sink", "upstream"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).isEmpty();
    }

    @Test
    void evaluate_matchWithNotExists_filtersWhenPresent() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("monitor-1"), new Spec("m1", "monitor"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("monitor-1"), NodeId.of("sink-1"))));

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "monitor", "sink", Direction.DEPENDENTS));
        String[] bindingNames = {"sink", "guard"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).isEmpty();
    }

    @Test
    void evaluate_matchWithNotExists_allowsWhenAbsent() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "monitor", "sink", Direction.DEPENDENTS));
        String[] bindingNames = {"sink", "guard"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0)).containsKey("sink");
        assertThat(bindings.get(0)).doesNotContainKey("guard");
    }

    @Test
    void evaluate_notExistsGlobal_filtersWhenTypePresent() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("v"), new Spec("v", "validator"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "", Direction.DEPENDENCIES));
        String[] bindingNames = {"tx", "guard"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).isEmpty();
    }

    @Test
    void evaluate_notExistsGlobal_allowsWhenTypeAbsent() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.NOT_EXISTS, "validator", "", Direction.DEPENDENCIES));
        String[] bindingNames = {"tx", "guard"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(1);
    }

    @Test
    void evaluate_matchWithReaches_findsTransitiveNodes() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("mid"), new Spec("mid", "middle"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("src"), new Spec("src", "source"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("tx"), NodeId.of("mid")),
                        new Dependency(NodeId.of("mid"), NodeId.of("src"))));

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.REACHES, "source", "", Direction.DEPENDENCIES));
        String[] bindingNames = {"tx", "src"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(1);
        assertThat(bindings.get(0).get("src").id()).isEqualTo(NodeId.of("src"));
    }

    @Test
    void evaluate_multipleMatchTypes_crossProduct() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("tx1"), new Spec("tx1", "transformer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("tx2"), new Spec("tx2", "transformer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("src"), new Spec("src", "source"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "transformer", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.MATCH, "source", "", Direction.DEPENDENCIES));
        String[] bindingNames = {"tx", "src"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(2);
    }

    @Test
    void evaluate_noMatchingNodes_returnsEmpty() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("db"), new Spec("db", "db"), HumanGating.NONE)),
                List.of());

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES));
        String[] bindingNames = {"sink"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).isEmpty();
    }

    @Test
    void evaluate_wildcardType_matchesAllTypes() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("a"), new Spec("a", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("b"), new Spec("b", "transformer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("c"), new Spec("c", "source"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("a"), NodeId.of("b")),
                        new Dependency(NodeId.of("a"), NodeId.of("c"))));

        List<PatternParameterDescriptor> patterns = List.of(
                new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "*", "a", Direction.DEPENDENCIES));
        String[] bindingNames = {"a", "dep"};

        List<Map<String, DesiredNode>> bindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        assertThat(bindings).hasSize(2);
    }
}
