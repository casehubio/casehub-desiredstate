package io.casehub.desiredstate.ts;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TsGraphRecorderTest {

    private static final Map<String, String> TYPE_REGISTRY =
            Map.of("test-type", TestNodeSpec.class.getName());

    private final TsGraphRecorder recorder = new TsGraphRecorder();
    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void singleGraphGoalCompilerMaterializesNodes() {
        var descriptor = new GraphDescriptor(
                "test", "simple", null, null,
                List.of(new NodeDescriptor.InlineNode("a", TestNodeSpec.class.getName(),
                        Map.of("value", "hello"), HumanGating.NONE)),
                List.of(),
                List.of(), null, List.of(), List.of());

        var compiler = recorder.createTsGoalCompiler(
                descriptor, TYPE_REGISTRY, List.of(), List.of(), List.of()).getValue();

        var result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        var graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.nodes().get(NodeId.of("a"))).isNotNull();
        assertThat(graph.nodes().get(NodeId.of("a")).spec()).isInstanceOf(TestNodeSpec.class);
        assertThat(((TestNodeSpec) graph.nodes().get(NodeId.of("a")).spec()).value()).isEqualTo("hello");
    }

    @Test
    void singleGraphWithDependencies() {
        var descriptor = new GraphDescriptor(
                "test", "deps", null, null,
                List.of(
                        new NodeDescriptor.InlineNode("a", TestNodeSpec.class.getName(),
                                Map.of("value", "source"), HumanGating.NONE),
                        new NodeDescriptor.InlineNode("b", TestNodeSpec.class.getName(),
                                Map.of("value", "sink"), HumanGating.NONE)),
                List.of(new DependencyDescriptor("b", "a")),
                List.of(), null, List.of(), List.of());

        var compiler = recorder.createTsGoalCompiler(
                descriptor, TYPE_REGISTRY, List.of(), List.of(), List.of()).getValue();

        var result = compiler.compile(null, factory);
        var graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependenciesOf(NodeId.of("b"))).contains(NodeId.of("a"));
    }

    @Test
    void lifecycleGoalCompilerCarriesForwardNodes() {
        var envelope = new TsLifecycleEnvelope("lifecycle", "test", "lc",
                List.of(
                        new TsEnvelopePhase("infra", "allPresent",
                                List.of(new TsEnvelopeNode("db", "test-type",
                                        Map.of("value", "postgres"), HumanGating.NONE, null)),
                                List.of()),
                        new TsEnvelopePhase("app", "allPresent",
                                List.of(new TsEnvelopeNode("api", "test-type",
                                        Map.of("value", "service"), HumanGating.NONE, null)),
                                List.of(new DependencyDescriptor("api", "db")))
                ));

        var compiler = recorder.createTsLifecycleGoalCompiler(
                envelope, TYPE_REGISTRY, List.of()).getValue();

        var result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.Lifecycle.class);
        var phases = ((CompilationResult.Lifecycle) result).phases();
        assertThat(phases).hasSize(2);

        assertThat(phases.get(0).graph().nodes()).hasSize(1);
        assertThat(phases.get(0).graph().nodes().get(NodeId.of("db"))).isNotNull();

        assertThat(phases.get(1).graph().nodes()).hasSize(2);
        assertThat(phases.get(1).graph().nodes().get(NodeId.of("db"))).isNotNull();
        assertThat(phases.get(1).graph().nodes().get(NodeId.of("api"))).isNotNull();
        assertThat(phases.get(1).graph().dependenciesOf(NodeId.of("api")))
                .contains(NodeId.of("db"));
    }

    @Test
    void lifecyclePhaseOverridesCarryForwardNode() {
        var envelope = new TsLifecycleEnvelope("lifecycle", "test", "override",
                List.of(
                        new TsEnvelopePhase("phase1", "allPresent",
                                List.of(new TsEnvelopeNode("db", "test-type",
                                        Map.of("value", "v1"), HumanGating.NONE, null)),
                                List.of()),
                        new TsEnvelopePhase("phase2", "never",
                                List.of(new TsEnvelopeNode("db", "test-type",
                                        Map.of("value", "v2"), HumanGating.NONE, null)),
                                List.of())
                ));

        var compiler = recorder.createTsLifecycleGoalCompiler(
                envelope, TYPE_REGISTRY, List.of()).getValue();

        var result = compiler.compile(null, factory);
        var phases = ((CompilationResult.Lifecycle) result).phases();

        var phase2Db = phases.get(1).graph().nodes().get(NodeId.of("db"));
        assertThat(((TestNodeSpec) phase2Db.spec()).value()).isEqualTo("v2");
    }
}
