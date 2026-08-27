package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlGraphRecorderTest {

    @NodeTypeId("test-source")
    public record TestSourceSpec(String name, String uri) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("test-source"); }
    }

    @NodeTypeId("bad-type")
    public record DivergentSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("actual-type"); }
    }

    @Test
    void compilesInlineNodesToDesiredStateGraph() {
        var descriptor = new GraphDescriptor(
                "test", "simple", null, null,
                List.of(
                        new NodeDescriptor.InlineNode("src", TestSourceSpec.class.getName(),
                                Map.of("name", "my-source", "uri", "s3://data"),
                                HumanGating.NONE)
                ),
                List.of(), List.of(), null, List.of(), List.of());

        Map<String, String> typeRegistry = Map.of("test-source", TestSourceSpec.class.getName());

        var recorder = new YamlGraphRecorder();
        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = recorder.createYamlGoalCompiler(
                descriptor, typeRegistry, Map.of()).getValue();

        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(1);

        DesiredNode node = graph.nodes().values().iterator().next();
        assertThat(node.id()).isEqualTo(NodeId.of("src"));
        assertThat(node.type()).isEqualTo(NodeType.of("test-source"));
        assertThat(node.spec()).isInstanceOf(TestSourceSpec.class);

        TestSourceSpec spec = (TestSourceSpec) node.spec();
        assertThat(spec.name()).isEqualTo("my-source");
        assertThat(spec.uri()).isEqualTo("s3://data");
    }

    @Test
    void resolvesVariablesBeforeDeserialization() {
        var descriptor = new GraphDescriptor(
                "test", "vars", null, null,
                List.of(
                        new NodeDescriptor.InlineNode("src", TestSourceSpec.class.getName(),
                                Map.of("name", "source", "uri", "${data_uri}"),
                                HumanGating.NONE)
                ),
                List.of(), List.of(), null, List.of(), List.of());

        Map<String, String> typeRegistry = Map.of("test-source", TestSourceSpec.class.getName());
        Map<String, String> variables = Map.of("data_uri", "s3://resolved");

        var recorder = new YamlGraphRecorder();
        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = recorder.createYamlGoalCompiler(
                descriptor, typeRegistry, variables).getValue();

        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        TestSourceSpec spec = (TestSourceSpec) graph.nodes().values().iterator().next().spec();
        assertThat(spec.uri()).isEqualTo("s3://resolved");
    }

    @Test
    void buildsDependencyEdges() {
        var descriptor = new GraphDescriptor(
                "test", "deps", null, null,
                List.of(
                        new NodeDescriptor.InlineNode("a", TestSourceSpec.class.getName(),
                                Map.of("name", "a", "uri", "x"), HumanGating.NONE),
                        new NodeDescriptor.InlineNode("b", TestSourceSpec.class.getName(),
                                Map.of("name", "b", "uri", "y"), HumanGating.NONE)
                ),
                List.of(new DependencyDescriptor("b", "a")),
                List.of(), null, List.of(), List.of());

        Map<String, String> typeRegistry = Map.of("test-source", TestSourceSpec.class.getName());

        var recorder = new YamlGraphRecorder();
        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = recorder.createYamlGoalCompiler(
                descriptor, typeRegistry, Map.of()).getValue();

        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.dependenciesOf(NodeId.of("b"))).contains(NodeId.of("a"));
    }

    @Test
    void humanGatingIsPreserved() {
        var descriptor = new GraphDescriptor(
                "test", "gating", null, null,
                List.of(
                        new NodeDescriptor.InlineNode("gated", TestSourceSpec.class.getName(),
                                Map.of("name", "x", "uri", "y"), HumanGating.PROVISION_ONLY)
                ),
                List.of(), List.of(), null, List.of(), List.of());

        Map<String, String> typeRegistry = Map.of("test-source", TestSourceSpec.class.getName());

        var recorder = new YamlGraphRecorder();
        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = recorder.createYamlGoalCompiler(
                descriptor, typeRegistry, Map.of()).getValue();

        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

        DesiredNode node = graph.nodes().values().iterator().next();
        assertThat(node.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test
    void detectsNodeTypeIdDivergence() {
        var descriptor = new GraphDescriptor(
                "test", "diverge", null, null,
                List.of(
                        new NodeDescriptor.InlineNode("bad", DivergentSpec.class.getName(),
                                Map.of(), HumanGating.NONE)
                ),
                List.of(), List.of(), null, List.of(), List.of());

        Map<String, String> typeRegistry = Map.of("bad-type", DivergentSpec.class.getName());

        var recorder = new YamlGraphRecorder();
        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = recorder.createYamlGoalCompiler(
                descriptor, typeRegistry, Map.of()).getValue();

        assertThatThrownBy(() -> compiler.compile(null, new DefaultDesiredStateGraphFactory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad-type")
                .hasMessageContaining("actual-type");
    }
}
