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

    private ResolvedGraphInvariant parameterizedInvariant(String methodName,
            List<PatternParameterDescriptor> patterns) {
        try {
            Class<?>[] paramTypes = new Class<?>[patterns.size()];
            for (int i = 0; i < patterns.size(); i++) {
                paramTypes[i] = patterns.get(i).kind() == PatternKind.NOT_EXISTS
                        ? Void.class : DesiredNode.class;
            }
            Method method = getClass().getMethod(methodName, paramTypes);
            return new ResolvedGraphInvariant(methodName, method, null, false, patterns);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResolvedGraphInvariant imperativeInvariant(String methodName) {
        try {
            Method method = getClass().getMethod(methodName, DesiredStateGraph.class);
            return new ResolvedGraphInvariant(methodName, method, null, true, List.of());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
