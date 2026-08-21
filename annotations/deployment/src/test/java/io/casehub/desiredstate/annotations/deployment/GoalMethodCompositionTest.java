package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.GoalMethod;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class GoalMethodCompositionTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    ComposableGraph.class, TestSpec.class, DynamicGoals.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record TestSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() {
            return NodeType.of("test-type");
        }
    }

    public record DynamicGoals(List<String> extraNodeIds) {}

    @DesiredState(namespace = "test", name = "composable")
    public interface ComposableGraph {

        @Node("base-node")
        default TestSpec baseNode() {
            return new TestSpec("base");
        }

        @GoalMethod
        default DesiredStateGraph compile(DynamicGoals goals, DesiredStateGraph base) {
            var graph = base;
            for (String id : goals.extraNodeIds()) {
                graph = graph.withNode(
                        new DesiredNode(NodeId.of(id), new TestSpec(id), HumanGating.NONE));
                graph = graph.withDependency(new Dependency(NodeId.of(id), NodeId.of("base-node")));
            }
            return graph;
        }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void staticBaseGraphPreserved() {
        var result = compiler.compile(new DynamicGoals(List.of()), factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);

        var graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.nodes().get(NodeId.of("base-node"))).isNotNull();
    }

    @Test
    void goalMethodExtendsDynamically() {
        var result = compiler.compile(new DynamicGoals(List.of("dynamic-a", "dynamic-b")), factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.nodes().get(NodeId.of("base-node"))).isNotNull();
        assertThat(graph.nodes().get(NodeId.of("dynamic-a"))).isNotNull();
        assertThat(graph.nodes().get(NodeId.of("dynamic-b"))).isNotNull();
    }

    @Test
    void dynamicNodesDependOnBase() {
        var result = compiler.compile(new DynamicGoals(List.of("dynamic-a")), factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.dependenciesOf(NodeId.of("dynamic-a")))
                .contains(NodeId.of("base-node"));
    }

    @Test
    void dynamicNodeSpecsAreCorrect() {
        var result = compiler.compile(new DynamicGoals(List.of("dynamic-a")), factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();

        var node = graph.nodes().get(NodeId.of("dynamic-a"));
        assertThat(node.spec()).isInstanceOf(TestSpec.class);
        assertThat(((TestSpec) node.spec()).data()).isEqualTo("dynamic-a");
        assertThat(node.type()).isEqualTo(NodeType.of("test-type"));
    }
}
