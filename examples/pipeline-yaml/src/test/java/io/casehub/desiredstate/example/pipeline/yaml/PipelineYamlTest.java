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
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.example.pipeline.DataSourceSpec;
import io.casehub.desiredstate.example.pipeline.IngestionSpec;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineYamlTest {

    private static GoalCompiler<Void> compiler;
    private static final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    private static final Map<String, String> TYPE_REGISTRY = Map.ofEntries(
            Map.entry("data-source", "io.casehub.desiredstate.example.pipeline.DataSourceSpec"),
            Map.entry("schema", "io.casehub.desiredstate.example.pipeline.SchemaSpec"),
            Map.entry("ingestion", "io.casehub.desiredstate.example.pipeline.IngestionSpec"),
            Map.entry("cleanser", "io.casehub.desiredstate.example.pipeline.CleanserSpec"),
            Map.entry("enricher", "io.casehub.desiredstate.example.pipeline.EnricherSpec"),
            Map.entry("validator", "io.casehub.desiredstate.example.pipeline.ValidatorSpec"),
            Map.entry("transformer", "io.casehub.desiredstate.example.pipeline.TransformerSpec"),
            Map.entry("sink", "io.casehub.desiredstate.example.pipeline.SinkSpec")
    );

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void buildFromYaml() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

        try (InputStream is = PipelineYamlTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/medallion-pipeline.yaml")) {
            assertThat(is).as("YAML file must be on classpath").isNotNull();
            YamlGraph yamlGraph = yamlMapper.readValue(is, YamlGraph.class);

            GraphDescriptor descriptor = toGraphDescriptor(yamlGraph);

            YamlGraphRecorder recorder = new YamlGraphRecorder();
            compiler = recorder.createYamlGoalCompiler(
                    descriptor, TYPE_REGISTRY,
                    yamlGraph.variables() != null ? yamlGraph.variables() : Map.of()).getValue();
        }
    }

    @Test
    void yamlGraphHasAllEightNodes() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes()).hasSize(8);
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

            for (String dep : yamlNode.dependsOn()) {
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
