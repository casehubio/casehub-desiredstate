package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlConditionalEvaluationTest {

    private static final DefaultDesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "source", "io.casehub.desiredstate.yaml.TestSourceSpec",
            "sink", "io.casehub.desiredstate.yaml.TestSinkSpec");

    @Test
    void whenTrue_nodeIncluded() {
        YamlGraph yamlGraph = buildGraph(
                Map.of("enabled", "true"),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, null, null),
                        "dst", new YamlNode("sink", Map.of("destination", "s3://out"),
                                List.of("src"), null, "${var.enabled}", null)));

        DesiredStateGraph graph = compile(yamlGraph);
        assertThat(graph.nodes()).containsKey(NodeId.of("dst"));
        assertThat(graph.nodes()).hasSize(2);
    }

    @Test
    void whenFalse_nodeExcluded() {
        YamlGraph yamlGraph = buildGraph(
                Map.of("enabled", "false"),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, null, null),
                        "dst", new YamlNode("sink", Map.of("destination", "s3://out"),
                                List.of((Object) Map.of("node", "src", "optional", true)), null, "${var.enabled}", null)));

        DesiredStateGraph graph = compile(yamlGraph);
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("dst"));
        assertThat(graph.nodes()).hasSize(1);
    }

    @Test
    void whenFalse_optionalDependencyRemoved() {
        YamlGraph yamlGraph = buildGraph(
                Map.of("debug", "false"),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, null, null),
                        "logger", new YamlNode("sink", Map.of("destination", "log://"),
                                List.of("src"), null, "${var.debug}", null),
                        "dst", new YamlNode("sink", Map.of("destination", "s3://out"),
                                List.of("src", Map.of("node", "logger", "optional", true)), null, null, null)));

        DesiredStateGraph graph = compile(yamlGraph);
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("logger"));
        assertThat(graph.nodes()).containsKey(NodeId.of("dst"));
        assertThat(graph.dependenciesOf(NodeId.of("dst"))).doesNotContain(NodeId.of("logger"));
        assertThat(graph.dependenciesOf(NodeId.of("dst"))).contains(NodeId.of("src"));
    }

    @Test
    void whenInvalidValue_throwsCompileError() {
        YamlGraph yamlGraph = buildGraph(
                Map.of("mode", "production"),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, null, null),
                        "dst", new YamlNode("sink", Map.of("destination", "s3://out"),
                                List.of((Object) Map.of("node", "src", "optional", true)), null, "${var.mode}", null)));

        assertThatThrownBy(() -> compile(yamlGraph))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("not a boolean");
    }

    @Test
    void noWhen_nodeAlwaysIncluded() {
        YamlGraph yamlGraph = buildGraph(
                Map.of(),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, null, null)));

        DesiredStateGraph graph = compile(yamlGraph);
        assertThat(graph.nodes()).hasSize(1);
    }

    @Test
    void whenYes_nodeIncluded() {
        YamlGraph yamlGraph = buildGraph(
                Map.of("enabled", "yes"),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, "${var.enabled}", null)));

        DesiredStateGraph graph = compile(yamlGraph);
        assertThat(graph.nodes()).containsKey(NodeId.of("src"));
    }

    @Test
    void whenNo_nodeExcluded() {
        YamlGraph yamlGraph = buildGraph(
                Map.of("enabled", "no"),
                Map.of(
                        "src", new YamlNode("source", Map.of("uri", "s3://test"), List.of(), null, "${var.enabled}", null)));

        DesiredStateGraph graph = compile(yamlGraph);
        assertThat(graph.nodes()).isEmpty();
    }

    private YamlGraph buildGraph(Map<String, String> variables, Map<String, YamlNode> nodes) {
        return new YamlGraph(
                new io.casehub.desiredstate.yaml.model.YamlDesiredState("test", "cond"),
                variables, nodes, List.of(), Map.of(), Map.of(), null, null, null);}

    @SuppressWarnings("unchecked")
    private DesiredStateGraph compile(YamlGraph yamlGraph) {
        List<NodeDescriptor> nodes = new java.util.ArrayList<>();
        List<DependencyDescriptor> deps = new java.util.ArrayList<>();
        for (Map.Entry<String, YamlNode> entry : yamlGraph.nodes().entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String specClassName = TYPE_REGISTRY.get(yamlNode.type());
            nodes.add(new NodeDescriptor.InlineNode(nodeId, specClassName,
                    yamlNode.spec() != null ? yamlNode.spec() : Map.of(), yamlNode.humanGating()));
            for (String dep : yamlNode.dependencyNodeIds()) {
                deps.add(new DependencyDescriptor(nodeId, dep));
            }
        }
        GraphDescriptor descriptor = new GraphDescriptor("test", "cond",
                null, null, nodes, deps, List.of(), null, List.of(), List.of());

        YamlGraphRecorder recorder = new YamlGraphRecorder();
        GoalCompiler<Void> compiler = recorder.createYamlGoalCompiler(
                descriptor, TYPE_REGISTRY, yamlGraph.variables(), List.of(), yamlGraph).getValue();
        return ((CompilationResult.SingleGraph) compiler.compile(null, FACTORY)).graph();
    }
}
