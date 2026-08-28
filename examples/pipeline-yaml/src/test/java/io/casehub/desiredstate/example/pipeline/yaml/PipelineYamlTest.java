package io.casehub.desiredstate.example.pipeline.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.pipeline.DataSourceSpec;
import io.casehub.desiredstate.example.pipeline.IngestionSpec;
import io.casehub.desiredstate.example.pipeline.MonitorSpec;
import io.casehub.desiredstate.example.pipeline.PipelineNodeTypes;
import io.casehub.desiredstate.example.pipeline.SinkSpec;
import io.casehub.desiredstate.example.pipeline.TransformerSpec;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.YamlGraphRecorder;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineYamlTest {

    private static GoalCompiler<Void> compiler;
    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    private static YamlGraph parsedYamlGraph;

    private static final Map<String, String> TYPE_REGISTRY = Map.ofEntries(
            Map.entry("data-source", "io.casehub.desiredstate.example.pipeline.DataSourceSpec"),
            Map.entry("schema", "io.casehub.desiredstate.example.pipeline.SchemaSpec"),
            Map.entry("ingestion", "io.casehub.desiredstate.example.pipeline.IngestionSpec"),
            Map.entry("cleanser", "io.casehub.desiredstate.example.pipeline.CleanserSpec"),
            Map.entry("enricher", "io.casehub.desiredstate.example.pipeline.EnricherSpec"),
            Map.entry("validator", "io.casehub.desiredstate.example.pipeline.ValidatorSpec"),
            Map.entry("transformer", "io.casehub.desiredstate.example.pipeline.TransformerSpec"),
            Map.entry("sink", "io.casehub.desiredstate.example.pipeline.SinkSpec"),
            Map.entry("ai-review", "io.casehub.desiredstate.example.pipeline.AiReviewSpec"),
            Map.entry("human-review", "io.casehub.desiredstate.example.pipeline.HumanReviewSpec"),
            Map.entry("monitor", "io.casehub.desiredstate.example.pipeline.MonitorSpec"),
            Map.entry("alerter", "io.casehub.desiredstate.example.pipeline.AlerterSpec")
    );

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void buildFromYaml() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        try (InputStream is = PipelineYamlTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/medallion-pipeline.yaml")) {
            assertThat(is).as("YAML file must be on classpath").isNotNull();
            YamlGraph yamlGraph = yamlMapper.readValue(is, YamlGraph.class);
            parsedYamlGraph = yamlGraph;

            GraphDescriptor descriptor = toGraphDescriptor(yamlGraph);

            List<io.casehub.desiredstate.annotations.runtime.ResolvedInvariant> invariants =
                    new ArrayList<>();
            for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlInvariant> inv :
                    yamlGraph.invariants().entrySet()) {
                invariants.add(io.casehub.desiredstate.yaml.YamlInvariantConverter
                        .toDeclarativeInvariant(inv.getKey(), inv.getValue()));
            }

            // Load monitoring module
            Map<String, io.casehub.desiredstate.yaml.model.YamlModule> availableModules =
                    new java.util.HashMap<>();
            try (InputStream modIs = PipelineYamlTest.class.getClassLoader()
                    .getResourceAsStream("META-INF/desiredstate/modules/monitoring.yaml")) {
                if (modIs != null) {
                    io.casehub.desiredstate.yaml.model.YamlModuleFile moduleFile =
                            yamlMapper.readValue(modIs, io.casehub.desiredstate.yaml.model.YamlModuleFile.class);
                    io.casehub.desiredstate.yaml.model.YamlModule module = moduleFile.toModule();
                    availableModules.put(module.name(), module);
                }
            }

            YamlGraphRecorder recorder = new YamlGraphRecorder();
            compiler = recorder.createYamlGoalCompiler(
                    descriptor, TYPE_REGISTRY,
                    yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                    invariants, yamlGraph, availableModules).getValue();
        }
    }

    @Test
    void yamlGraphHasAllNineNodes() {
        DesiredStateGraph graph = compileSingleGraph();
        // 8 declared + 4 forEach-expanded (2 templates × 2 regions)
        // + 2 module nodes (pipe-monitor.monitor, pipe-monitor.alerter)
        // - debug-validator excluded. Rule doesn't fire (module monitor satisfies guard)
        assertThat(graph.nodes()).hasSize(14);
    }

    @Test
    void bronzeLayerNodes() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("csv-source")).type())
                .isEqualTo(PipelineNodeTypes.DATA_SOURCE);
        assertThat(graph.nodes().get(NodeId.of("csv-source")).spec())
                .isInstanceOf(DataSourceSpec.class);

        DataSourceSpec dsSpec = (DataSourceSpec) graph.nodes().get(NodeId.of("csv-source")).spec();
        assertThat(dsSpec.name()).isEqualTo("customers");
        assertThat(dsSpec.format()).isEqualTo("CSV");

        assertThat(graph.nodes().get(NodeId.of("customer-schema")).type())
                .isEqualTo(PipelineNodeTypes.SCHEMA);
        assertThat(graph.nodes().get(NodeId.of("csv-ingest")).type())
                .isEqualTo(PipelineNodeTypes.INGESTION);
    }

    @Test
    void bronzeDependencyChain() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("csv-ingest"), NodeId.of("csv-source")));
    }

    @Test
    void silverLayerDependencies() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("dedup-cleanser")))
                .contains(NodeId.of("csv-ingest"), NodeId.of("customer-schema"));
        assertThat(graph.dependenciesOf(NodeId.of("geo-enricher")))
                .contains(NodeId.of("dedup-cleanser"));
        assertThat(graph.dependenciesOf(NodeId.of("quality-validator")))
                .contains(NodeId.of("geo-enricher"), NodeId.of("customer-schema"));
    }

    @Test
    void goldLayerHumanGating() {
        DesiredStateGraph graph = compileSingleGraph();
        DesiredNode txNode = graph.nodes().get(NodeId.of("aggregate-tx"));
        assertThat(txNode.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
        assertThat(txNode.type()).isEqualTo(PipelineNodeTypes.TRANSFORMER);
        assertThat(txNode.spec()).isInstanceOf(TransformerSpec.class);

        DesiredNode sinkNode = graph.nodes().get(NodeId.of("warehouse-sink"));
        assertThat(sinkNode.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
        assertThat(sinkNode.type()).isEqualTo(PipelineNodeTypes.SINK);
    }

    @Test
    void goldDependencyChain() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("aggregate-tx")))
                .contains(NodeId.of("quality-validator"));
        assertThat(graph.dependenciesOf(NodeId.of("warehouse-sink")))
                .contains(NodeId.of("aggregate-tx"));
    }

    @Test
    void variableSubstitutionWorks() {
        DesiredStateGraph graph = compileSingleGraph();
        DataSourceSpec dsSpec = (DataSourceSpec) graph.nodes().get(NodeId.of("csv-source")).spec();
        assertThat(dsSpec.uri()).isEqualTo("s3://data/customers.csv");

        IngestionSpec ingSpec = (IngestionSpec) graph.nodes().get(NodeId.of("csv-ingest")).spec();
        assertThat(ingSpec.batchSize()).isEqualTo(1000);
    }

    @Test
    void sinkSpecFieldsDeserialized() {
        DesiredStateGraph graph = compileSingleGraph();
        SinkSpec sinkSpec = (SinkSpec) graph.nodes().get(NodeId.of("warehouse-sink")).spec();
        assertThat(sinkSpec.destination()).isEqualTo("s3://warehouse/gold/");
        assertThat(sinkSpec.format()).isEqualTo("parquet");
        assertThat(sinkSpec.partitionKeys()).containsExactly("date");
    }

    @Test
    void yamlInvariant_everySinkHasUpstream_passesForMedallionPipeline() {
        assertThat(parsedYamlGraph.invariants()).containsKey("every-sink-has-upstream");
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("warehouse-sink"))).isNotNull();
        assertThat(graph.dependenciesOf(NodeId.of("warehouse-sink")))
                .contains(NodeId.of("aggregate-tx"));
    }

    @Test
    void yamlWhen_debugModeFalse_debugValidatorExcluded() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("debug-validator"));
        // 8 declared + 4 forEach + 2 module = 14 (rule suppressed by module)
        assertThat(graph.nodes()).hasSize(14);
    }


    /**
     * The medallion pipeline's gold-layer transformer keeps failing.
     * The YAML fault policy declares a two-tier escalation:
     * - After 3 failures: an AI agent reviews the issue
     * - After 5 failures: a human operator gets pulled in
     * <p>
     * This test verifies that the YAML-declared fault policy builds into
     * a working ThresholdFaultPolicy that resolves ${fault.*} templates
     * against the actual fault event context.
     */
    @Test
    void faultPolicyEscalation_transformerFails_aiThenHumanReview() {
        // The YAML declares a fault policy — verify it parsed
        assertThat(parsedYamlGraph.faultPolicy()).hasSize(1);
        var yamlPolicy = parsedYamlGraph.faultPolicy().getFirst();
        assertThat(yamlPolicy.namespace()).isEqualTo("pipeline-escalation");
        assertThat(yamlPolicy.tiers()).hasSize(2);

        // Build the ThresholdFaultPolicy from the YAML declaration
        io.casehub.desiredstate.api.ThresholdFaultPolicy policy =
                io.casehub.desiredstate.yaml.YamlFaultPolicyBuilder.build(
                        yamlPolicy, TYPE_REGISTRY,
                        new io.casehub.desiredstate.api.InMemoryFaultCountStore());

        // Set up: the gold-layer transformer is in the graph and keeps failing
        DesiredStateGraph graph = compileSingleGraph();
        io.casehub.desiredstate.api.FaultEvent event = new io.casehub.desiredstate.api.FaultEvent(
                NodeId.of("aggregate-tx"),
                io.casehub.desiredstate.api.FaultType.PROVISION_FAILED,
                "Connection timeout to data warehouse");
        var actualState = new io.casehub.desiredstate.api.ActualState(Map.of());

        // Failures 1-2: the system retries automatically — no escalation yet
        assertThat(policy.onFault("prod", event, graph, actualState)).isEmpty();
        assertThat(policy.onFault("prod", event, graph, actualState)).isEmpty();

        // Failure 3: AI agent is called in to review the issue
        var aiMutations = policy.onFault("prod", event, graph, actualState);
        assertThat(aiMutations).as("3rd failure triggers AI review").isNotEmpty();

        // Verify the AI review node carries the fault context from the template
        io.casehub.desiredstate.api.GraphMutation.AddNode aiAdd = aiMutations.stream()
                                                                             .filter(m -> m instanceof io.casehub.desiredstate.api.GraphMutation.AddNode)
                                                                             .map(m -> (io.casehub.desiredstate.api.GraphMutation.AddNode) m)
                                                                             .findFirst().orElseThrow();

        io.casehub.desiredstate.example.pipeline.AiReviewSpec aiSpec =
                (io.casehub.desiredstate.example.pipeline.AiReviewSpec) aiAdd.node().spec();
        assertThat(aiSpec.targetNodeId()).isEqualTo(NodeId.of("aggregate-tx"));
        assertThat(aiSpec.errorDetail()).isEqualTo("Connection timeout to data warehouse");
    }

    @Test
    void yamlRule_ensureMonitoring_suppressedByModuleMonitor() {
        DesiredStateGraph graph = compileSingleGraph();
        // Module provides pipe-monitor.monitor as a dependent of warehouse-sink,
        // so the ensure-monitoring rule's notExists guard is satisfied — no rule-generated node
        assertThat(graph.nodes()).doesNotContainKey(NodeId.of("monitor-warehouse-sink"));
        assertThat(graph.nodes()).containsKey(NodeId.of("pipe-monitor.monitor"));
    }

    @Test
    void yamlRule_ensureMonitoring_oneMonitorFromModule() {
        DesiredStateGraph graph = compileSingleGraph();
        long monitorCount = graph.nodes().values().stream()
                                 .filter(n -> n.type().equals(io.casehub.desiredstate.api.NodeType.of("monitor")))
                                 .count();
        assertThat(monitorCount).isEqualTo(1);
    }


    @Test
    void forEach_regionalSourcesExpanded() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes()).containsKey(NodeId.of("regional-source.us-east"));
        assertThat(graph.nodes()).containsKey(NodeId.of("regional-source.eu-west"));
        assertThat(graph.nodes()).containsKey(NodeId.of("regional-ingest.us-east"));
        assertThat(graph.nodes()).containsKey(NodeId.of("regional-ingest.eu-west"));
    }

    @Test
    void forEach_alignedDependencies() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("regional-ingest.us-east")))
                .contains(NodeId.of("regional-source.us-east"));
        assertThat(graph.dependenciesOf(NodeId.of("regional-ingest.eu-west")))
                .contains(NodeId.of("regional-source.eu-west"));
        assertThat(graph.dependenciesOf(NodeId.of("regional-ingest.us-east")))
                .doesNotContain(NodeId.of("regional-source.eu-west"));
    }

    @Test
    void forEach_dependsOnFixedSchema() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("regional-ingest.us-east")))
                .contains(NodeId.of("customer-schema"));
        assertThat(graph.dependenciesOf(NodeId.of("regional-ingest.eu-west")))
                .contains(NodeId.of("customer-schema"));
    }

    @Test
    void forEach_eachVariableInterpolated() {
        DesiredStateGraph graph = compileSingleGraph();
        DataSourceSpec usSpec = (DataSourceSpec) graph.nodes()
                .get(NodeId.of("regional-source.us-east")).spec();
        assertThat(usSpec.name()).isEqualTo("regional-us-east");
        assertThat(usSpec.uri()).isEqualTo("s3://data/us-east/regional.csv");
    }

    @Test
    void module_monitoringImported_nodesAliased() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes()).containsKey(NodeId.of("pipe-monitor.monitor"));
        assertThat(graph.nodes()).containsKey(NodeId.of("pipe-monitor.alerter"));
    }

    @Test
    void module_monitorDependsOnImportingNode() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("pipe-monitor.monitor")))
                .contains(NodeId.of("warehouse-sink"));
    }

    @Test
    void module_alerterDependsOnMonitor() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.dependenciesOf(NodeId.of("pipe-monitor.alerter")))
                .contains(NodeId.of("pipe-monitor.monitor"));
    }

    @Test
    void module_parameterResolvedInSpec() {
        DesiredStateGraph graph = compileSingleGraph();
        MonitorSpec monSpec = (MonitorSpec) graph.nodes()
                .get(NodeId.of("pipe-monitor.monitor")).spec();
        assertThat(monSpec.target()).isEqualTo("warehouse-sink");

        io.casehub.desiredstate.example.pipeline.AlerterSpec alertSpec =
                (io.casehub.desiredstate.example.pipeline.AlerterSpec) graph.nodes()
                        .get(NodeId.of("pipe-monitor.alerter")).spec();
        assertThat(alertSpec.email()).isEqualTo("pipeline-ops@example.com");
    }

    private DesiredStateGraph compileSingleGraph() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }

    private static GraphDescriptor toGraphDescriptor(YamlGraph yamlGraph) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        List<DependencyDescriptor> deps = new ArrayList<>();

        for (Map.Entry<String, YamlNode> entry : yamlGraph.nodes().entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String specClassName = TYPE_REGISTRY.get(yamlNode.type());

            nodes.add(new NodeDescriptor.InlineNode(
                    nodeId, specClassName,
                    yamlNode.spec() != null ? yamlNode.spec() : Map.of(),
                    yamlNode.humanGating()));

            for (String dep : yamlNode.dependencyNodeIds()) {
                deps.add(new DependencyDescriptor(nodeId, dep));
            }
        }

        return new GraphDescriptor(
                yamlGraph.desiredState().namespace(),
                yamlGraph.desiredState().name(),
                null, null, nodes, deps,
                List.of(), null, List.of(), List.of());
    }
}
