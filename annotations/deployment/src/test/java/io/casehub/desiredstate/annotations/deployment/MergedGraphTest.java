package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.Node;
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

class MergedGraphTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    BaseGraph.class, ExtensionNode.class, TestSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record TestSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("test"); }
    }

    @DesiredState(namespace = "merge", name = "test")
    public interface BaseGraph {
        @Node("base-node")
        default TestSpec baseNode() {
            return new TestSpec("base");
        }
    }

    @DeclareNode(namespace = "merge", name = "test", id = "extension-node")
    @DependsOn("base-node")
    public static class ExtensionNode implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("ext"); }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void mergedGraphContainsBothInterfaceAndClassNodes() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.nodes().get(NodeId.of("base-node"))).isNotNull();
        assertThat(graph.nodes().get(NodeId.of("extension-node"))).isNotNull();
    }

    @Test
    void crossModelStringDependencyResolved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("extension-node"), NodeId.of("base-node")));
    }

    @Test
    void interfaceNodeSpecPreserved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("base-node")).spec())
                .isInstanceOf(TestSpec.class);
        assertThat(((TestSpec) graph.nodes().get(NodeId.of("base-node")).spec()).data())
                .isEqualTo("base");
    }

    @Test
    void classNodeSpecPreserved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("extension-node")).spec())
                .isInstanceOf(ExtensionNode.class);
    }

    private DesiredStateGraph compileSingleGraph() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }
}
