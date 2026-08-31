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

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphInvariantEngineTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final GraphInvariantEngine engine = new GraphInvariantEngine();

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    // --- parameterized: structural violation when anchor fails to expand ---

    public static void sinkMustHaveUpstream(DesiredNode sink, DesiredNode upstream) {}

    @Test
    void parameterizedViolationWhenAnchorFailsToExpand() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("ds1"), new Spec("d1", "data-source"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("sink3"), new Spec("s3", "sink"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("sink1"), NodeId.of("ds1"))));

        var invariant = parameterizedInvariant("sinkMustHaveUpstream",
                List.of(
                        new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                        new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "data-source", "sink", Direction.DEPENDENCIES)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
        assertTrue(ex.violations().get(0).message().contains("sink3"));
    }

    // --- parameterized: passes when all anchors expand ---

    @Test
    void parameterizedPassesWhenAllAnchorsExpand() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("ds1"), new Spec("d1", "data-source"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("sink1"), NodeId.of("ds1"))));

        var invariant = parameterizedInvariant("sinkMustHaveUpstream",
                List.of(
                        new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                        new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "data-source", "sink", Direction.DEPENDENCIES)));

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    // --- vacuously true when no @Match anchors ---

    @Test
    void vacuouslyTrueWhenNoMatchAnchors() {
        var graph = factory.of(
                List.of(new DesiredNode(NodeId.of("ds1"), new Spec("d1", "data-source"), HumanGating.NONE)),
                List.of());

        var invariant = parameterizedInvariant("sinkMustHaveUpstream",
                List.of(
                        new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                        new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "data-source", "sink", Direction.DEPENDENCIES)));

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    // --- imperative: violation ---

    public static void checkNoOrphans(DesiredStateGraph graph) {
        throw new GraphViolationException("Orphaned node found", NodeId.of("orphan1"));
    }

    @Test
    void imperativeViolation() {
        var graph = factory.of(List.of(), List.of());
        var invariant = imperativeInvariant("checkNoOrphans");

        var ex = assertThrows(GraphInvariantViolationsException.class,
                () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
    }

    // --- imperative: passes ---

    public static void checkAlwaysPasses(DesiredStateGraph graph) {}

    @Test
    void imperativePasses() {
        var graph = factory.of(List.of(), List.of());
        var invariant = imperativeInvariant("checkAlwaysPasses");
        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    // --- empty invariant list ---

    @Test
    void emptyInvariantListNoException() {
        var graph = factory.of(List.of(), List.of());
        assertDoesNotThrow(() -> engine.validate(graph, List.of()));
    }

    // --- multiple violations collected ---

    @Test
    void multipleViolationsCollected() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("sink2"), new Spec("s2", "sink"), HumanGating.NONE)),
                List.of());

        var invariant = parameterizedInvariant("sinkMustHaveUpstream",
                List.of(
                        new PatternParameterDescriptor(PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES),
                        new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "data-source", "sink", Direction.DEPENDENCIES)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                () -> engine.validate(graph, List.of(invariant)));
        assertEquals(2, ex.violations().size());
    }

    // --- helpers ---


    public static void lbMinTargets(DesiredNode lb, DesiredNode target) {}


    @Test
    void expansionMinCountViolation_tooFewDeps() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("lb1"), new Spec("lb1", "load-balancer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("t1"), new Spec("t1", "target"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("t1"), NodeId.of("lb1"))));

        var invariant = parameterizedInvariant("lbMinTargets",
                                               List.of(
                                                       new PatternParameterDescriptor(PatternKind.MATCH, "load-balancer", "", Direction.DEPENDENCIES),
                                                       new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "target", "lb",
                                                                                      Direction.DEPENDENTS, 2, -1)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                              () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
        assertTrue(ex.violations().get(0).message().contains("at least 2"));
    }

    @Test
    void expansionMinCountPasses_enoughDeps() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("lb1"), new Spec("lb1", "load-balancer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("t1"), new Spec("t1", "target"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("t2"), new Spec("t2", "target"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("t3"), new Spec("t3", "target"), HumanGating.NONE)),
                List.of(
                        new Dependency(NodeId.of("t1"), NodeId.of("lb1")),
                        new Dependency(NodeId.of("t2"), NodeId.of("lb1")),
                        new Dependency(NodeId.of("t3"), NodeId.of("lb1"))));

        var invariant = parameterizedInvariant("lbMinTargets",
                                               List.of(
                                                       new PatternParameterDescriptor(PatternKind.MATCH, "load-balancer", "", Direction.DEPENDENCIES),
                                                       new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "target", "lb",
                                                                                      Direction.DEPENDENTS, 2, -1)));

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    @Test
    void expansionMaxCountViolation_tooManyDeps() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("svc1"), new Spec("svc1", "service"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("db1"), new Spec("db1", "database"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("db2"), new Spec("db2", "database"), HumanGating.NONE)),
                List.of(
                        new Dependency(NodeId.of("svc1"), NodeId.of("db1")),
                        new Dependency(NodeId.of("svc1"), NodeId.of("db2"))));

        var invariant = parameterizedInvariant("lbMinTargets",
                                               List.of(
                                                       new PatternParameterDescriptor(PatternKind.MATCH, "service", "", Direction.DEPENDENCIES),
                                                       new PatternParameterDescriptor(PatternKind.DIRECT_DEP, "database", "lb",
                                                                                      Direction.DEPENDENCIES, -1, 1)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                              () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
        assertTrue(ex.violations().get(0).message().contains("at most 1"));
    }

    public static void haMinimum(DesiredNode instance) {}


    @Test
    void matchMinCountViolation_tooFewNodes() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("i1"), new Spec("i1", "compute"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("i2"), new Spec("i2", "compute"), HumanGating.NONE)),
                List.of());

        var invariant = parameterizedInvariant("haMinimum",
                                               List.of(new PatternParameterDescriptor(
                                                       PatternKind.MATCH, "compute", "", Direction.DEPENDENCIES, 3, -1)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                              () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
        assertTrue(ex.violations().get(0).message().contains("at least 3"));
        assertTrue(ex.violations().get(0).message().contains("found 2"));
    }

    @Test
    void matchMinCountPasses_exactlyEnough() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("i1"), new Spec("i1", "compute"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("i2"), new Spec("i2", "compute"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("i3"), new Spec("i3", "compute"), HumanGating.NONE)),
                List.of());

        var invariant = parameterizedInvariant("haMinimum",
                                               List.of(new PatternParameterDescriptor(
                                                       PatternKind.MATCH, "compute", "", Direction.DEPENDENCIES, 3, -1)));

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    @Test
    void matchMaxCountViolation_tooManyNodes() {
        var graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("cp1"), new Spec("cp1", "control-plane"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("cp2"), new Spec("cp2", "control-plane"), HumanGating.NONE)),
                List.of());

        var invariant = parameterizedInvariant("haMinimum",
                                               List.of(new PatternParameterDescriptor(
                                                       PatternKind.MATCH, "control-plane", "", Direction.DEPENDENCIES, 1, 1)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                              () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
        assertTrue(ex.violations().get(0).message().contains("at most 1"));
    }

    @Test
    void matchSingletonPasses() {
        var graph = factory.of(
                List.of(new DesiredNode(NodeId.of("cp1"), new Spec("cp1", "control-plane"), HumanGating.NONE)),
                List.of());

        var invariant = parameterizedInvariant("haMinimum",
                                               List.of(new PatternParameterDescriptor(
                                                       PatternKind.MATCH, "control-plane", "", Direction.DEPENDENCIES, 1, 1)));

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    @Test
    void matchSingletonViolation_zeroNodes() {
        var graph = factory.of(List.of(), List.of());

        var invariant = parameterizedInvariant("haMinimum",
                                               List.of(new PatternParameterDescriptor(
                                                       PatternKind.MATCH, "control-plane", "", Direction.DEPENDENCIES, 1, 1)));

        var ex = assertThrows(GraphInvariantViolationsException.class,
                              () -> engine.validate(graph, List.of(invariant)));
        assertEquals(1, ex.violations().size());
        assertTrue(ex.violations().get(0).message().contains("at least 1"));
    }

    private ResolvedInvariant parameterizedInvariant(String methodName,
            List<PatternParameterDescriptor> patterns) {
        try {
            Class<?>[] paramTypes = new Class<?>[patterns.size()];
            for (int i = 0; i < patterns.size(); i++) {
                paramTypes[i] = patterns.get(i).kind() == PatternKind.NOT_EXISTS
                        ? Void.class : DesiredNode.class;
            }
            Method method = getClass().getMethod(methodName, paramTypes);
            return new ResolvedInvariant.ParameterizedReflectiveInvariant(methodName, method, null, patterns);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResolvedInvariant imperativeInvariant(String methodName) {
        try {
            Method method = getClass().getMethod(methodName, DesiredStateGraph.class);
            return new ResolvedInvariant.ImperativeInvariant(methodName, method, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
