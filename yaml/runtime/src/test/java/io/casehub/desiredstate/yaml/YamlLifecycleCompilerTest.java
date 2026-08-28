package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.model.YamlDesiredState;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlLifecycle;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.model.YamlPhase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlLifecycleCompilerTest {

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "db", "io.casehub.desiredstate.yaml.YamlLifecycleCompilerTest$Spec",
            "app", "io.casehub.desiredstate.yaml.YamlLifecycleCompilerTest$Spec",
            "monitor", "io.casehub.desiredstate.yaml.YamlLifecycleCompilerTest$Spec");

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void lifecycle_twoPhases_producesLifecycleResult() {
        YamlGraph yamlGraph = new YamlGraph(
                new YamlDesiredState("test", "lifecycle"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("database", new YamlNode("db",
                                        Map.of("name", "pg", "typeValue", "db"),
                                        List.of(), null, null))),
                        new YamlPhase("app", "never",
                                Map.of("api-server", new YamlNode("app",
                                        Map.of("name", "api", "typeValue", "app"),
                                        List.of("database"), null, null))))));

        var recorder = new YamlGraphRecorder();
        var compiler = recorder.createYamlLifecycleGoalCompiler(
                yamlGraph, TYPE_REGISTRY, Map.of(), List.of()).getValue();

        CompilationResult result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.Lifecycle.class);

        List<Phase> phases = ((CompilationResult.Lifecycle) result).phases();
        assertThat(phases).hasSize(2);

        assertThat(phases.get(0).id()).isEqualTo("infra");
        assertThat(phases.get(0).graph().nodes()).containsKey(NodeId.of("database"));
        assertThat(phases.get(0).graph().nodes()).hasSize(1);
        assertThat(phases.get(0).isTerminal()).isFalse();

        assertThat(phases.get(1).id()).isEqualTo("app");
        assertThat(phases.get(1).graph().nodes()).containsKey(NodeId.of("api-server"));
        assertThat(phases.get(1).graph().nodes()).containsKey(NodeId.of("database"));
        assertThat(phases.get(1).graph().nodes()).hasSize(2);
        assertThat(phases.get(1).isTerminal()).isTrue();
    }

    @Test
    void lifecycle_carryForward_dependenciesResolveAcrossPhases() {
        YamlGraph yamlGraph = new YamlGraph(
                new YamlDesiredState("test", "deps"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("database", new YamlNode("db",
                                        Map.of("name", "pg", "typeValue", "db"),
                                        List.of(), null, null))),
                        new YamlPhase("app", "never",
                                Map.of("api-server", new YamlNode("app",
                                        Map.of("name", "api", "typeValue", "app"),
                                        List.of("database"), null, null))))));

        var recorder = new YamlGraphRecorder();
        var compiler = recorder.createYamlLifecycleGoalCompiler(
                yamlGraph, TYPE_REGISTRY, Map.of(), List.of()).getValue();

        CompilationResult result = compiler.compile(null, factory);
        List<Phase> phases = ((CompilationResult.Lifecycle) result).phases();

        DesiredStateGraph appGraph = phases.get(1).graph();
        assertThat(appGraph.dependenciesOf(NodeId.of("api-server")))
                .contains(NodeId.of("database"));
    }

    @Test
    void lifecycle_overrideNodeInLaterPhase() {
        YamlGraph yamlGraph = new YamlGraph(
                new YamlDesiredState("test", "override"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("database", new YamlNode("db",
                                        Map.of("name", "pg-v1", "typeValue", "db"),
                                        List.of(), null, null))),
                        new YamlPhase("app", "never",
                                Map.of("database", new YamlNode("db",
                                        Map.of("name", "pg-v2", "typeValue", "db"),
                                        List.of(), null, null))))));

        var recorder = new YamlGraphRecorder();
        var compiler = recorder.createYamlLifecycleGoalCompiler(
                yamlGraph, TYPE_REGISTRY, Map.of(), List.of()).getValue();

        CompilationResult result = compiler.compile(null, factory);
        List<Phase> phases = ((CompilationResult.Lifecycle) result).phases();

        Spec infraSpec = (Spec) phases.get(0).graph().nodes()
                .get(NodeId.of("database")).spec();
        assertThat(infraSpec.name()).isEqualTo("pg-v1");

        Spec appSpec = (Spec) phases.get(1).graph().nodes()
                .get(NodeId.of("database")).spec();
        assertThat(appSpec.name()).isEqualTo("pg-v2");
    }

    @Test
    void lifecycle_threePhases_carryForwardAccumulates() {
        YamlGraph yamlGraph = new YamlGraph(
                new YamlDesiredState("test", "three"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("database", new YamlNode("db",
                                        Map.of("name", "pg", "typeValue", "db"),
                                        List.of(), null, null))),
                        new YamlPhase("app", "allPresent",
                                Map.of("api-server", new YamlNode("app",
                                        Map.of("name", "api", "typeValue", "app"),
                                        List.of("database"), null, null))),
                        new YamlPhase("obs", "never",
                                Map.of("monitor", new YamlNode("monitor",
                                        Map.of("name", "mon", "typeValue", "monitor"),
                                        List.of("api-server"), null, null))))));

        var recorder = new YamlGraphRecorder();
        var compiler = recorder.createYamlLifecycleGoalCompiler(
                yamlGraph, TYPE_REGISTRY, Map.of(), List.of()).getValue();

        CompilationResult result = compiler.compile(null, factory);
        List<Phase> phases = ((CompilationResult.Lifecycle) result).phases();
        assertThat(phases).hasSize(3);

        DesiredStateGraph obsGraph = phases.get(2).graph();
        assertThat(obsGraph.nodes()).containsKey(NodeId.of("database"));
        assertThat(obsGraph.nodes()).containsKey(NodeId.of("api-server"));
        assertThat(obsGraph.nodes()).containsKey(NodeId.of("monitor"));
        assertThat(obsGraph.nodes()).hasSize(3);
        assertThat(obsGraph.dependenciesOf(NodeId.of("monitor")))
                .contains(NodeId.of("api-server"));
    }
}
