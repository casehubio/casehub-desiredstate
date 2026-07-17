package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.platform.agent.NoOpAgentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionBackendTest {

    private PipelineWorld world;
    private DefaultExecutionBackend backend;

    @BeforeEach
    void setUp() {
        world = new PipelineWorld();
        backend = new DefaultExecutionBackend(world);
    }

    @Test
    void handles_processingTypesOnly() {
        assertThat(backend.handles(node("src", PipelineNodeTypes.DATA_SOURCE,
                new DataSourceSpec("src", "json", "kafka://x")))).isFalse();
        assertThat(backend.handles(node("sch", PipelineNodeTypes.SCHEMA,
                new SchemaSpec("sch", List.of("a"), 1)))).isFalse();
        assertThat(backend.handles(node("ai", PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(NodeId.of("x"), "err")))).isFalse();
        assertThat(backend.handles(node("hr", PipelineNodeTypes.HUMAN_REVIEW,
                new HumanReviewSpec(NodeId.of("x"), "err", "escalation")))).isFalse();

        assertThat(backend.handles(node("ing", PipelineNodeTypes.INGESTION,
                new IngestionSpec("src", 1000, "json")))).isTrue();
        assertThat(backend.handles(node("cl", PipelineNodeTypes.CLEANSER,
                new CleanserSpec(List.of("dedupe"), true, "DROP")))).isTrue();
        assertThat(backend.handles(node("en", PipelineNodeTypes.ENRICHER,
                new EnricherSpec("geo", List.of("id"), List.of("country"))))).isTrue();
        assertThat(backend.handles(node("val", PipelineNodeTypes.VALIDATOR,
                new ValidatorSpec("sch", 0.95, false)))).isTrue();
        assertThat(backend.handles(node("tx", PipelineNodeTypes.TRANSFORMER,
                new TransformerSpec(List.of("agg"), List.of("reshape"), "parquet")))).isTrue();
        assertThat(backend.handles(node("sk", PipelineNodeTypes.SINK,
                new SinkSpec("s3://out", "parquet", List.of("date"))))).isTrue();
    }

    @Test
    void ingestion_failsWhenSourceMissing() {
        DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
                new IngestionSpec("missing-source", 1000, "json"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(ingestion), List.of());

        ProvisionResult result = backend.provision(ingestion, new ProvisionContext("test", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("missing-source");
    }

    @Test
    void ingestion_succeedsWhenSourcePresent() {
        world.registerSource(NodeId.of("src"),
                new PipelineWorld.DataSourceEntry("src", "json", "kafka://x"));
        DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
                new IngestionSpec("src", 1000, "json"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(ingestion), List.of());

        ProvisionResult result = backend.provision(ingestion, new ProvisionContext("test", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(world.stageState(NodeId.of("ingest")))
                .isEqualTo(PipelineWorld.StageState.RUNNING);
    }

    @Test
    void deprovision_removesStage() {
        world.setStage(NodeId.of("tx"),
                new PipelineWorld.StageEntry(PipelineNodeTypes.TRANSFORMER,
                        PipelineWorld.StageState.RUNNING, null, null, 0, 0, 0, null));
        DesiredNode transformer = node("tx", PipelineNodeTypes.TRANSFORMER,
                new TransformerSpec(List.of("agg"), List.of("reshape"), "parquet"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(transformer), List.of());

        DeprovisionResult result = backend.deprovision(transformer,
                new DeprovisionContext("test", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
        assertThat(world.stageState(NodeId.of("tx"))).isNull();
    }

    @Test
    void provisioner_delegatesProcessingStagesToBackend() {
        world.registerSource(NodeId.of("src"),
                new PipelineWorld.DataSourceEntry("src", "json", "kafka://x"));
        DesiredNode source = node("src", PipelineNodeTypes.DATA_SOURCE,
                new DataSourceSpec("src", "json", "kafka://x"));
        DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
                new IngestionSpec("src", 1000, "json"));
        DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();
        DesiredStateGraph graph = graphFactory.of(
                List.of(source, ingestion),
                List.of(new Dependency(NodeId.of("ingest"), NodeId.of("src"))));

        PipelineProvisioner provisioner = new PipelineProvisioner(
                world, new NoOpAgentProvider(), List.of(backend));

        ProvisionResult result = provisioner.provision(ingestion, new ProvisionContext("test", graph));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(world.stageState(NodeId.of("ingest")))
                .isEqualTo(PipelineWorld.StageState.RUNNING);
    }

    @Test
    void provisioner_noBackend_returnsFailed() {
        PipelineProvisioner provisioner = new PipelineProvisioner(
                world, new NoOpAgentProvider(), List.of());

        DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
                new IngestionSpec("src", 1000, "json"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(ingestion), List.of());

        ProvisionResult result = provisioner.provision(ingestion, new ProvisionContext("test", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("No execution backend");
    }

    @Test
    void provisioner_ambiguousBackends_throws() {
        ExecutionBackend backend1 = new DefaultExecutionBackend(world);
        ExecutionBackend backend2 = new DefaultExecutionBackend(world);
        PipelineProvisioner provisioner = new PipelineProvisioner(
                world, new NoOpAgentProvider(), List.of(backend1, backend2));

        DesiredNode ingestion = node("ingest", PipelineNodeTypes.INGESTION,
                new IngestionSpec("src", 1000, "json"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(ingestion), List.of());

        assertThatThrownBy(() ->
                provisioner.provision(ingestion, new ProvisionContext("test", graph)))
            .isInstanceOf(AmbiguousBackendException.class)
            .hasMessageContaining("Multiple execution backends")
            .hasMessageContaining("ingest");
    }

    @Test
    void provisioner_dataSource_handledDirectly() {
        PipelineProvisioner provisioner = new PipelineProvisioner(
                world, new NoOpAgentProvider(), List.of());

        DesiredNode source = node("src", PipelineNodeTypes.DATA_SOURCE,
                new DataSourceSpec("src", "json", "kafka://x"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(source), List.of());

        ProvisionResult result = provisioner.provision(source, new ProvisionContext("test", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(world.hasSource(NodeId.of("src"))).isTrue();
    }

    @Test
    void provisioner_deprovisionAmbiguous_throws() {
        ExecutionBackend backend1 = new DefaultExecutionBackend(world);
        ExecutionBackend backend2 = new DefaultExecutionBackend(world);
        PipelineProvisioner provisioner = new PipelineProvisioner(
                world, new NoOpAgentProvider(), List.of(backend1, backend2));

        DesiredNode transformer = node("tx", PipelineNodeTypes.TRANSFORMER,
                new TransformerSpec(List.of("agg"), List.of("reshape"), "parquet"));
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory()
                .of(List.of(transformer), List.of());

        assertThatThrownBy(() ->
                provisioner.deprovision(transformer, new DeprovisionContext("test", graph)))
            .isInstanceOf(AmbiguousBackendException.class);
    }

    private DesiredNode node(String id, NodeType type, NodeSpec spec) {
        return new DesiredNode(NodeId.of(id), type, spec, HumanGating.NONE);
    }
}
