package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ClassBasedDependencyTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    DepTarget.class, DepSource.class, MixedSource.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    @DeclareNode(namespace = "dep", name = "test", id = "target")
    public static class DepTarget implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("t"); }
    }

    @DeclareNode(namespace = "dep", name = "test", id = "source")
    @DependsOn(nodes = DepTarget.class)
    public static class DepSource implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("s"); }
    }

    @DeclareNode(namespace = "dep", name = "test", id = "mixed")
    @DependsOn(value = "target", nodes = DepSource.class)
    public static class MixedSource implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("m"); }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void typeSafeDependencyResolved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("source"), NodeId.of("target")));
    }

    @Test
    void mixedDependenciesBothResolved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("mixed"), NodeId.of("target")))
                .contains(new Dependency(NodeId.of("mixed"), NodeId.of("source")));
    }

    private DesiredStateGraph compileSingleGraph() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }
}
