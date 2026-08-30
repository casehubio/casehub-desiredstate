package io.casehub.desiredstate.example.pipeline.ts;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.example.pipeline.DataSourceSpec;
import io.casehub.desiredstate.example.pipeline.MonitorSpec;
import io.casehub.desiredstate.example.pipeline.PipelineNodeTypes;
import io.casehub.desiredstate.example.pipeline.SinkSpec;
import io.casehub.desiredstate.example.pipeline.TransformerSpec;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.ts.TsEnvelope;
import io.casehub.desiredstate.ts.TsEnvelopeNode;
import io.casehub.desiredstate.ts.TsGraphRecorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineTsTest {

    private static final Map<String, String> TYPE_REGISTRY = Map.ofEntries(
            Map.entry("data-source", DataSourceSpec.class.getName()),
            Map.entry("transformer", TransformerSpec.class.getName()),
            Map.entry("sink", SinkSpec.class.getName()),
            Map.entry("monitor", MonitorSpec.class.getName()));

    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private static GoalCompiler<Void> compilerWithoutRules;
    private static GoalCompiler<Void> compilerWithCrossSurfaceRule;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void buildFromJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        try (InputStream is = PipelineTsTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/medallion-pipeline.ds.json")) {
            assertThat(is).as(".ds.json must be on classpath").isNotNull();
            TsEnvelope envelope = mapper.readValue(is, TsEnvelope.class);

            GraphDescriptor descriptor = toGraphDescriptor(envelope);
            TsGraphRecorder recorder = new TsGraphRecorder();

            compilerWithoutRules = recorder.createTsGoalCompiler(
                    descriptor, TYPE_REGISTRY, List.of(), List.of(), List.of()).getValue();

            var ruleDescriptor = new io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor(
                    "ensureMonitoring", false,
                    List.of(
                            new io.casehub.desiredstate.annotations.runtime.PatternParameterDescriptor(
                                    io.casehub.desiredstate.annotations.runtime.PatternKind.MATCH,
                                    "sink", null, null),
                            new io.casehub.desiredstate.annotations.runtime.PatternParameterDescriptor(
                                    io.casehub.desiredstate.annotations.runtime.PatternKind.NOT_EXISTS,
                                    "monitor", "sink",
                                    io.casehub.desiredstate.annotations.runtime.Direction.DEPENDENTS)),
                    EnsureMonitoringRule.class.getName());

            compilerWithCrossSurfaceRule = recorder.createTsGoalCompiler(
                    descriptor, TYPE_REGISTRY, List.of(),
                    List.of(ruleDescriptor), List.of()).getValue();
        }
    }

    @Test
    void tsGraphHasFourDeclaredNodes() {
        DesiredStateGraph graph = compileWithoutRules();
        assertThat(graph.nodes()).hasSize(4);
    }

    @Test
    void bronzeLayerSources() {
        DesiredStateGraph graph = compileWithoutRules();
        assertThat(graph.nodes().get(NodeId.of("source-us-east")).type())
                .isEqualTo(PipelineNodeTypes.DATA_SOURCE);
        assertThat(graph.nodes().get(NodeId.of("source-eu-west")).type())
                .isEqualTo(PipelineNodeTypes.DATA_SOURCE);

        DataSourceSpec spec = (DataSourceSpec) graph.nodes()
                .get(NodeId.of("source-us-east")).spec();
        assertThat(spec.name()).isEqualTo("customers-us-east");
        assertThat(spec.uri()).isEqualTo("s3://us-east/customers.csv");
    }

    @Test
    void transformerDependsOnBothSources() {
        DesiredStateGraph graph = compileWithoutRules();
        assertThat(graph.dependenciesOf(NodeId.of("csv-ingest")))
                .contains(NodeId.of("source-us-east"), NodeId.of("source-eu-west"));
    }

    @Test
    void sinkHasHumanGating() {
        DesiredStateGraph graph = compileWithoutRules();
        DesiredNode sink = graph.nodes().get(NodeId.of("warehouse-sink"));
        assertThat(sink.humanGating())
                .isEqualTo(io.casehub.desiredstate.api.HumanGating.PROVISION_ONLY);
    }

    @Test
    void sinkDependsOnTransformer() {
        DesiredStateGraph graph = compileWithoutRules();
        assertThat(graph.dependenciesOf(NodeId.of("warehouse-sink")))
                .contains(NodeId.of("csv-ingest"));
    }

    @Test
    void crossSurfaceRule_addsMonitorForSink() {
        DesiredStateGraph graph = compileWithCrossSurfaceRule();
        assertThat(graph.nodes()).hasSize(5);

        DesiredNode monitor = graph.nodes().values().stream()
                .filter(n -> n.type().equals(NodeType.of("monitor")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a monitor node from cross-surface rule"));

        assertThat(monitor.spec()).isInstanceOf(MonitorSpec.class);
        assertThat(((MonitorSpec) monitor.spec()).target()).isEqualTo("warehouse-sink");
    }

    @Test
    void crossSurfaceRule_monitorDependsOnSink() {
        DesiredStateGraph graph = compileWithCrossSurfaceRule();
        DesiredNode monitor = graph.nodes().values().stream()
                .filter(n -> n.type().equals(NodeType.of("monitor")))
                .findFirst().orElseThrow();

        assertThat(graph.dependenciesOf(monitor.id()))
                .contains(NodeId.of("warehouse-sink"));
    }

    private DesiredStateGraph compileWithoutRules() {
        return ((CompilationResult.SingleGraph) compilerWithoutRules.compile(null, factory)).graph();
    }

    private DesiredStateGraph compileWithCrossSurfaceRule() {
        return ((CompilationResult.SingleGraph) compilerWithCrossSurfaceRule.compile(null, factory)).graph();
    }

    private static GraphDescriptor toGraphDescriptor(TsEnvelope envelope) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        for (TsEnvelopeNode en : envelope.nodes()) {
            nodes.add(new NodeDescriptor.InlineNode(
                    en.id(), TYPE_REGISTRY.get(en.type()), en.spec(), en.humanGating()));
        }
        return new GraphDescriptor(
                envelope.namespace(), envelope.name(), null, null,
                nodes, envelope.dependencies(),
                List.of(), null, List.of(), List.of());
    }
}
