package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class DesiredStateAnnotationsProcessorTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    SimpleGraph.class, TestNodeSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record TestNodeSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("test-type");
        }
    }

    @DesiredState(namespace = "test", name = "simple")
    public interface SimpleGraph {

        @Node("node-a")
        default TestNodeSpec nodeA() {
            return new TestNodeSpec("a-data");
        }

        @Node(value = "node-b", humanGating = HumanGating.PROVISION_ONLY)
        @DependsOn("node-a")
        default TestNodeSpec nodeB() {
            return new TestNodeSpec("b-data");
        }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void generatesGoalCompilerFromAnnotatedInterface() {
        CompilationResult result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(2);
    }

    @Test
    void nodesHaveCorrectTypes() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("node-a")).type())
                .isEqualTo(NodeType.of("test-type"));
        assertThat(graph.nodes().get(NodeId.of("node-b")).type())
                .isEqualTo(NodeType.of("test-type"));
    }

    @Test
    void nodesHaveCorrectSpecs() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("node-a")).spec())
                .isInstanceOf(TestNodeSpec.class);
        assertThat(((TestNodeSpec) graph.nodes().get(NodeId.of("node-a")).spec()).data())
                .isEqualTo("a-data");
    }

    @Test
    void dependenciesAreWired() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("node-b"), NodeId.of("node-a")));
    }

    @Test
    void humanGatingIsPreserved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("node-a")).humanGating())
                .isEqualTo(HumanGating.NONE);
        assertThat(graph.nodes().get(NodeId.of("node-b")).humanGating())
                .isEqualTo(HumanGating.PROVISION_ONLY);
    }

    private DesiredStateGraph compileSingleGraph() {
        CompilationResult result = compiler.compile(null, factory);
        return ((CompilationResult.SingleGraph) result).graph();
    }
}
