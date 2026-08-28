package io.casehub.desiredstate.example.pipeline.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.example.pipeline.DataSourceSpec;
import io.casehub.desiredstate.example.pipeline.MonitorSpec;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.YamlGraphRecorder;
import io.casehub.desiredstate.yaml.YamlInvariantConverter;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LifecyclePipelineTest {

    private static GoalCompiler<Void> compiler;
    private static final DefaultDesiredStateGraphFactory factory =
            new DefaultDesiredStateGraphFactory();

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
            Map.entry("monitor", "io.casehub.desiredstate.example.pipeline.MonitorSpec"));

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void buildFromYaml() throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = LifecyclePipelineTest.class.getClassLoader()
                .getResourceAsStream("META-INF/desiredstate/lifecycle-pipeline.yaml")) {
            assertThat(is).as("Lifecycle YAML must be on classpath").isNotNull();
            YamlGraph yamlGraph = yamlMapper.readValue(is, YamlGraph.class);

            List<io.casehub.desiredstate.annotations.runtime.ResolvedInvariant> invariants =
                    new ArrayList<>();
            for (var inv : yamlGraph.invariants().entrySet()) {
                invariants.add(YamlInvariantConverter.toDeclarativeInvariant(
                        inv.getKey(), inv.getValue()));
            }

            YamlGraphRecorder recorder = new YamlGraphRecorder();
            compiler = recorder.createYamlLifecycleGoalCompiler(
                    yamlGraph, TYPE_REGISTRY,
                    yamlGraph.variables() != null ? yamlGraph.variables() : Map.of(),
                    invariants).getValue();
        }
    }

    @Test
    void lifecycle_producesThreePhases() {
        CompilationResult result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.Lifecycle.class);
        List<Phase> phases = ((CompilationResult.Lifecycle) result).phases();
        assertThat(phases).hasSize(3);
        assertThat(phases.get(0).id()).isEqualTo("infrastructure");
        assertThat(phases.get(1).id()).isEqualTo("processing");
        assertThat(phases.get(2).id()).isEqualTo("delivery");
    }

    @Test
    void infrastructure_hasTwoNodes() {
        List<Phase> phases = compilePhases();
        DesiredStateGraph infra = phases.get(0).graph();
        assertThat(infra.nodes()).hasSize(2);
        assertThat(infra.nodes()).containsKey(NodeId.of("csv-source"));
        assertThat(infra.nodes()).containsKey(NodeId.of("customer-schema"));
    }

    @Test
    void infrastructure_completionIsAllPresent() {
        List<Phase> phases = compilePhases();
        assertThat(phases.get(0).isTerminal()).isFalse();
    }

    @Test
    void processing_carryForwardInfrastructureNodes() {
        List<Phase> phases = compilePhases();
        DesiredStateGraph proc = phases.get(1).graph();
        assertThat(proc.nodes()).containsKey(NodeId.of("csv-source"));
        assertThat(proc.nodes()).containsKey(NodeId.of("customer-schema"));
        assertThat(proc.nodes()).containsKey(NodeId.of("csv-ingest"));
        assertThat(proc.nodes()).containsKey(NodeId.of("dedup-cleanser"));
        assertThat(proc.nodes()).containsKey(NodeId.of("quality-validator"));
    }

    @Test
    void processing_crossPhaseDependenciesResolve() {
        List<Phase> phases = compilePhases();
        DesiredStateGraph proc = phases.get(1).graph();
        assertThat(proc.dependenciesOf(NodeId.of("csv-ingest")))
                .contains(NodeId.of("csv-source"));
        assertThat(proc.dependenciesOf(NodeId.of("dedup-cleanser")))
                .contains(NodeId.of("customer-schema"));
    }

    @Test
    void delivery_carryForwardAllPriorNodes() {
        List<Phase> phases = compilePhases();
        DesiredStateGraph delivery = phases.get(2).graph();
        assertThat(delivery.nodes()).containsKey(NodeId.of("csv-source"));
        assertThat(delivery.nodes()).containsKey(NodeId.of("aggregate-tx"));
        assertThat(delivery.nodes()).containsKey(NodeId.of("warehouse-sink"));
    }

    @Test
    void delivery_isTerminal() {
        List<Phase> phases = compilePhases();
        assertThat(phases.get(2).isTerminal()).isTrue();
    }

    @Test
    void delivery_ruleAddsMonitor() {
        List<Phase> phases = compilePhases();
        DesiredStateGraph delivery = phases.get(2).graph();
        assertThat(delivery.nodes()).containsKey(NodeId.of("monitor-warehouse-sink"));

        MonitorSpec monSpec = (MonitorSpec) delivery.nodes()
                .get(NodeId.of("monitor-warehouse-sink")).spec();
        assertThat(monSpec.target()).isEqualTo("warehouse-sink");
    }

    @Test
    void infrastructure_variableSubstitution() {
        List<Phase> phases = compilePhases();
        DesiredStateGraph infra = phases.get(0).graph();
        DataSourceSpec dsSpec = (DataSourceSpec) infra.nodes()
                .get(NodeId.of("csv-source")).spec();
        assertThat(dsSpec.uri()).isEqualTo("s3://data/customers.csv");
    }

    private List<Phase> compilePhases() {
        return ((CompilationResult.Lifecycle) compiler.compile(null, factory)).phases();
    }
}
