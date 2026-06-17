package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.SimpleTransitionExecutor;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.platform.agent.*;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PipelineBlueprint builder, PipelineGoalCompiler dependency wiring,
 * and PipelineWorld simulation layer (provisioner, adapter, event source).
 * Plain JUnit — no Quarkus container needed.
 */
class PipelineTest {

    private DesiredStateGraphFactory factory;
    private PipelineGoalCompiler compiler;
    private TransitionPlanner planner;
    private PipelineWorld world;
    private PipelineProvisioner provisioner;
    private PipelineActualStateAdapter adapter;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        compiler = new PipelineGoalCompiler();
        planner = new TransitionPlanner();
        world = new PipelineWorld();
        provisioner = new PipelineProvisioner(world, new NoOpAgentProvider());
        adapter = new PipelineActualStateAdapter(world);
    }

    /**
     * Standard 8-stage pipeline matching the spec example.
     */
    private PipelineBlueprint standardBlueprint() {
        return PipelineBlueprint.builder()
            .source("clickstream", "json", "kafka://clicks")
            .schema("click-schema", List.of("userId", "pageUrl", "timestamp"), 1)
            .ingestion("click-ingest", "clickstream", 1000, "json")
            .cleanser("click-clean", List.of("deduplicate", "normalize-timestamps"), true, "DROP")
            .enricher("geo-enrich", "geo-lookup", List.of("userId"), List.of("country", "city"))
            .validator("quality-gate", "click-schema", 0.95, true)
            .transformer("session-agg", List.of("sessionize", "count-pages"), List.of("group-by-session"), "parquet")
            .sink("warehouse", "s3://analytics/sessions", "parquet", List.of("date", "country"))
            .build();
    }

    @Test
    void buildBasicPipeline() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // --- 8 nodes ---
        assertThat(graph.nodes()).hasSize(8);

        // --- 2 roots: datasource and schema ---
        Set<NodeId> roots = graph.roots();
        assertThat(roots).containsExactlyInAnyOrder(
            NodeId.of("clickstream"), NodeId.of("click-schema"));

        // --- ingestion depends on datasource ---
        assertThat(graph.dependenciesOf(NodeId.of("click-ingest")))
            .containsExactly(NodeId.of("clickstream"));

        // --- cleanser fan-in: depends on all ingestions + all schemas ---
        assertThat(graph.dependenciesOf(NodeId.of("click-clean")))
            .containsExactlyInAnyOrder(
                NodeId.of("click-ingest"), NodeId.of("click-schema"));

        // --- enricher depends on all cleansers ---
        assertThat(graph.dependenciesOf(NodeId.of("geo-enrich")))
            .containsExactly(NodeId.of("click-clean"));

        // --- validator fan-in: depends on all enrichers + all schemas ---
        assertThat(graph.dependenciesOf(NodeId.of("quality-gate")))
            .containsExactlyInAnyOrder(
                NodeId.of("geo-enrich"), NodeId.of("click-schema"));

        // --- transformer depends on all validators ---
        assertThat(graph.dependenciesOf(NodeId.of("session-agg")))
            .containsExactly(NodeId.of("quality-gate"));

        // --- sink depends on all transformers ---
        assertThat(graph.dependenciesOf(NodeId.of("warehouse")))
            .containsExactly(NodeId.of("session-agg"));

        // --- medallion layer assignments ---
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("clickstream")).type()))
            .hasValue(PipelineLayer.BRONZE);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("click-schema")).type()))
            .hasValue(PipelineLayer.BRONZE);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("click-ingest")).type()))
            .hasValue(PipelineLayer.BRONZE);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("click-clean")).type()))
            .hasValue(PipelineLayer.SILVER);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("geo-enrich")).type()))
            .hasValue(PipelineLayer.SILVER);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("quality-gate")).type()))
            .hasValue(PipelineLayer.SILVER);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("session-agg")).type()))
            .hasValue(PipelineLayer.GOLD);
        assertThat(PipelineNodeTypes.layerOf(graph.nodes().get(NodeId.of("warehouse")).type()))
            .hasValue(PipelineLayer.GOLD);
    }

    @Test
    void topologicalOrderMatchesMedallionLayers() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Plan against empty actual state — everything is new
        ActualState empty = new ActualState(Map.of());
        TransitionPlan plan = planner.plan(graph, empty);

        assertThat(plan.removals()).isEmpty();
        assertThat(plan.additions()).hasSize(8);

        // Extract layer sequence from the planned additions
        List<PipelineLayer> layerOrder = plan.additions().stream()
            .map(step -> PipelineNodeTypes.layerOf(step.node().type()).orElseThrow())
            .collect(Collectors.toList());

        // All Bronze nodes must appear before any Silver node
        int lastBronze = -1;
        int firstSilver = Integer.MAX_VALUE;
        int lastSilver = -1;
        int firstGold = Integer.MAX_VALUE;

        for (int i = 0; i < layerOrder.size(); i++) {
            PipelineLayer layer = layerOrder.get(i);
            if (layer == PipelineLayer.BRONZE) {
                lastBronze = i;
            } else if (layer == PipelineLayer.SILVER) {
                if (i < firstSilver) firstSilver = i;
                lastSilver = i;
            } else if (layer == PipelineLayer.GOLD) {
                if (i < firstGold) firstGold = i;
            }
        }

        assertThat(lastBronze)
            .as("All Bronze nodes must appear before any Silver node")
            .isLessThan(firstSilver);
        assertThat(lastSilver)
            .as("All Silver nodes must appear before any Gold node")
            .isLessThan(firstGold);
    }

    // --- Tasks 5-7: PipelineWorld simulation layer tests ---

    @Test
    void provisionFullPipeline() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Plan against empty actual state — everything needs provisioning
        ActualState empty = new ActualState(Map.of());
        TransitionPlan plan = planner.plan(graph, empty);

        // Register the lookup source the enricher needs (external dependency)
        world.registerLookupSource("geo-lookup", new PipelineWorld.LookupSourceEntry("geo-lookup"));

        // Execute all additions via SimpleTransitionExecutor
        SimpleTransitionExecutor executor = new SimpleTransitionExecutor(provisioner);
        TransitionResult result = executor.execute(plan).await().indefinitely();

        // All 8 nodes should succeed
        assertThat(result.outcomes()).hasSize(8);
        result.outcomes().forEach((id, outcome) ->
            assertThat(outcome)
                .as("Node %s should succeed", id.value())
                .isInstanceOf(StepOutcome.Succeeded.class));

        // Verify world state: all processing stages are RUNNING
        assertThat(world.stageState(NodeId.of("click-ingest"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("click-clean"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("geo-enrich"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("quality-gate"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("session-agg"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("warehouse"))).isEqualTo(PipelineWorld.StageState.RUNNING);

        // Verify data sources and schemas are registered
        assertThat(world.hasSource(NodeId.of("clickstream"))).isTrue();
        assertThat(world.hasSchema("click-schema")).isTrue();

        // Read actual state and verify all nodes report PRESENT
        ActualState actual = adapter.readActual(graph);
        assertThat(actual.statuses()).hasSize(8);
        actual.statuses().forEach((id, status) ->
            assertThat(status)
                .as("Node %s should be PRESENT", id.value())
                .isEqualTo(NodeStatus.PRESENT));
    }

    @Test
    void schemaIncompatibility_failsProvision() {
        // Create an enricher node that needs a lookup source
        DesiredNode enricher = new DesiredNode(
            NodeId.of("geo-enrich"), PipelineNodeTypes.ENRICHER,
            new EnricherSpec("geo-lookup", List.of("userId"), List.of("country", "city")),
            false);

        // Do NOT register the lookup source — provisioning should fail
        DesiredStateGraph graph = factory.of(List.of(enricher), List.of());
        ProvisionContext ctx = new ProvisionContext("default", graph);

        ProvisionResult result = provisioner.provision(enricher, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("geo-lookup");
    }

    @Test
    void deprovisionCascade_downstreamGoesIdle() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Provision the full pipeline first
        world.registerLookupSource("geo-lookup", new PipelineWorld.LookupSourceEntry("geo-lookup"));
        SimpleTransitionExecutor executor = new SimpleTransitionExecutor(provisioner);
        ActualState empty = new ActualState(Map.of());
        TransitionPlan plan = planner.plan(graph, empty);
        executor.execute(plan).await().indefinitely();

        // All stages should be RUNNING before deprovision
        assertThat(world.stageState(NodeId.of("click-clean"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("geo-enrich"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("quality-gate"))).isEqualTo(PipelineWorld.StageState.RUNNING);

        // Deprovision the cleanser — downstream should cascade to IDLE
        DesiredNode cleanser = graph.nodes().get(NodeId.of("click-clean"));
        DeprovisionContext deprovCtx = new DeprovisionContext("default", graph);
        DeprovisionResult deprovResult = provisioner.deprovision(cleanser, deprovCtx);

        assertThat(deprovResult).isInstanceOf(DeprovisionResult.Success.class);

        // Cleanser is removed (null state), downstream stages cascaded to IDLE
        assertThat(world.stageState(NodeId.of("click-clean"))).isNull();
        assertThat(world.stageState(NodeId.of("geo-enrich"))).isEqualTo(PipelineWorld.StageState.IDLE);
        assertThat(world.stageState(NodeId.of("quality-gate"))).isEqualTo(PipelineWorld.StageState.IDLE);
    }

    // --- Task 8: Fault policy tests ---

    @Test
    void provisionFailure_fullEscalationChain() {
        ProvisionEscalationFaultPolicy policy = new ProvisionEscalationFaultPolicy(world);

        // Create an ingestion node and put it in a graph
        DesiredNode ingestNode = new DesiredNode(
            NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
            new IngestionSpec("clickstream", 1000, "json"), false);
        DesiredStateGraph graph = factory.of(List.of(ingestNode), List.of());

        FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "source unavailable");

        // Events 1-3: retry phase — all return empty
        for (int i = 0; i < 3; i++) {
            assertThat(policy.onFault(fault, graph))
                .as("Fault %d should return empty (retry phase)", i + 1)
                .isEmpty();
        }

        // Event 4: creates AI_REVIEW node
        List<GraphMutation> mutations4 = policy.onFault(fault, graph);
        assertThat(mutations4).hasSize(1);
        assertThat(mutations4.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        GraphMutation.AddNode addAiReview = (GraphMutation.AddNode) mutations4.get(0);
        assertThat(addAiReview.node().id()).isEqualTo(NodeId.of("ai-review-ingest"));
        assertThat(addAiReview.node().type()).isEqualTo(PipelineNodeTypes.AI_REVIEW);
        assertThat(addAiReview.node().requiresHuman()).isFalse();

        // Apply mutation to graph
        graph = graph.withMutation(addAiReview);

        // Set AI_REVIEW as PENDING in world → next onFault returns empty (wait)
        world.addReview(NodeId.of("ai-review-ingest"), NodeId.of("ingest"));
        assertThat(policy.onFault(fault, graph)).isEmpty();

        // Set AI_REVIEW as UNRESOLVED → next onFault creates HUMAN_REVIEW
        world.setAiReviewOutcome(NodeId.of("ingest"), false);
        List<GraphMutation> mutationsHuman = policy.onFault(fault, graph);
        assertThat(mutationsHuman).hasSize(1);
        assertThat(mutationsHuman.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        GraphMutation.AddNode addHumanReview = (GraphMutation.AddNode) mutationsHuman.get(0);
        assertThat(addHumanReview.node().id()).isEqualTo(NodeId.of("human-review-ingest"));
        assertThat(addHumanReview.node().type()).isEqualTo(PipelineNodeTypes.HUMAN_REVIEW);
        assertThat(addHumanReview.node().requiresHuman()).isTrue();

        // Apply mutation and add to world → next onFault returns empty (idempotency)
        graph = graph.withMutation(addHumanReview);
        world.addReview(NodeId.of("human-review-ingest"), NodeId.of("ingest"));
        assertThat(policy.onFault(fault, graph)).isEmpty();
    }

    @Test
    void provisionFailure_aiReviewResolves() {
        ProvisionEscalationFaultPolicy policy = new ProvisionEscalationFaultPolicy(world);

        DesiredNode ingestNode = new DesiredNode(
            NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
            new IngestionSpec("clickstream", 1000, "json"), false);
        DesiredStateGraph graph = factory.of(List.of(ingestNode), List.of());

        FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "source unavailable");

        // Faults 1-3: retry phase
        for (int i = 0; i < 3; i++) {
            policy.onFault(fault, graph);
        }

        // Fault 4: creates AI_REVIEW
        List<GraphMutation> mutations = policy.onFault(fault, graph);
        assertThat(mutations).hasSize(1);
        graph = graph.withMutation(mutations.get(0));

        // Set AI_REVIEW as RESOLVED in world
        world.addReview(NodeId.of("ai-review-ingest"), NodeId.of("ingest"));
        world.setAiReviewOutcome(NodeId.of("ingest"), true);

        // Next onFault returns empty — AI resolved it, no human escalation
        assertThat(policy.onFault(fault, graph)).isEmpty();

        // Verify no HUMAN_REVIEW was created
        assertThat(graph.nodes().containsKey(NodeId.of("human-review-ingest"))).isFalse();
    }

    @Test
    void validationQuarantine_humanReview() {
        QuarantineFaultPolicy policy = new QuarantineFaultPolicy(world);

        // Create a validator node
        DesiredNode validator = new DesiredNode(
            NodeId.of("quality-gate"), PipelineNodeTypes.VALIDATOR,
            new ValidatorSpec("click-schema", 0.95, true), false);
        DesiredStateGraph graph = factory.of(List.of(validator), List.of());

        // Set the validator as QUARANTINED in world
        world.setStage(NodeId.of("quality-gate"),
            new PipelineWorld.StageEntry(PipelineNodeTypes.VALIDATOR, PipelineWorld.StageState.QUARANTINED,
                "click-schema", null, 0, 0, 5, "quality threshold breached"));

        FaultEvent fault = new FaultEvent(NodeId.of("quality-gate"), FaultType.NODE_DEGRADED,
            "quality threshold breached");

        List<GraphMutation> mutations = policy.onFault(fault, graph);
        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        GraphMutation.AddNode addHuman = (GraphMutation.AddNode) mutations.get(0);
        assertThat(addHuman.node().id()).isEqualTo(NodeId.of("human-review-quality-gate"));
        assertThat(addHuman.node().type()).isEqualTo(PipelineNodeTypes.HUMAN_REVIEW);
        assertThat(addHuman.node().requiresHuman()).isTrue();
    }

    @Test
    void schemaDrift_humanReviewOnly() {
        SchemaDriftFaultPolicy policy = new SchemaDriftFaultPolicy();

        // Create a schema node
        DesiredNode schema = new DesiredNode(
            NodeId.of("click-schema"), PipelineNodeTypes.SCHEMA,
            new SchemaSpec("click-schema", List.of("userId", "pageUrl", "timestamp"), 1), false);
        DesiredStateGraph graph = factory.of(List.of(schema), List.of());

        FaultEvent fault = new FaultEvent(NodeId.of("click-schema"), FaultType.NODE_DEGRADED,
            "schema version drift detected");

        List<GraphMutation> mutations = policy.onFault(fault, graph);
        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        GraphMutation.AddNode addHuman = (GraphMutation.AddNode) mutations.get(0);
        assertThat(addHuman.node().id()).isEqualTo(NodeId.of("human-review-click-schema"));

        // Assert no RemoveNode mutations returned
        Optional<GraphMutation> removeNode = mutations.stream()
            .filter(m -> m instanceof GraphMutation.RemoveNode)
            .findAny();
        assertThat(removeNode).isEmpty();
    }

    // --- Task 9: Integration tests ---

    @Test
    void fullReconciliationCycle() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);
        world.registerLookupSource("geo-lookup", new PipelineWorld.LookupSourceEntry("geo-lookup"));

        // Phase 1: All ABSENT → provision all
        ActualState actual = adapter.readActual(graph);
        TransitionPlan plan = planner.plan(graph, actual);
        assertThat(plan.additions()).hasSize(8);
        for (OrderedStep step : plan.additions()) {
            ProvisionResult result = provisioner.provision(step.node(), new ProvisionContext("test", graph));
            assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        }

        // Phase 2: All PRESENT → empty plan (reconciled)
        actual = adapter.readActual(graph);
        plan = planner.plan(graph, actual);
        assertThat(plan.isEmpty()).isTrue();

        // Phase 3: Fail ingestion → re-provision
        world.failStage(NodeId.of("click-ingest"), "Connection lost");
        actual = adapter.readActual(graph);
        assertThat(actual.statusOf(NodeId.of("click-ingest"))).hasValue(NodeStatus.ABSENT);
        plan = planner.plan(graph, actual);
        assertThat(plan.isEmpty()).isFalse();
        assertThat(plan.additions().stream().anyMatch(s -> s.node().id().equals(NodeId.of("click-ingest"))))
            .isTrue();

        world.clearStageError(NodeId.of("click-ingest"));
        for (OrderedStep step : plan.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }

        // Phase 4: All PRESENT again after recovery
        actual = adapter.readActual(graph);
        for (NodeId nodeId : graph.nodes().keySet()) {
            assertThat(actual.statusOf(nodeId))
                .as("%s should be PRESENT after recovery", nodeId.value())
                .hasValue(NodeStatus.PRESENT);
        }
    }

    @Test
    void faultGeneratedNodes_neverInBlueprint() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        for (DesiredNode node : graph.nodes().values()) {
            assertThat(node.type())
                .as("GoalCompiler should never emit AI_REVIEW nodes")
                .isNotEqualTo(PipelineNodeTypes.AI_REVIEW);
            assertThat(node.type())
                .as("GoalCompiler should never emit HUMAN_REVIEW nodes")
                .isNotEqualTo(PipelineNodeTypes.HUMAN_REVIEW);
        }
    }

    // --- #35: layerOf() returns Optional ---

    @Test
    void layerOf_returnsPresentForAllPipelineStageTypes() {
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.DATA_SOURCE)).hasValue(PipelineLayer.BRONZE);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.SCHEMA)).hasValue(PipelineLayer.BRONZE);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.INGESTION)).hasValue(PipelineLayer.BRONZE);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.CLEANSER)).hasValue(PipelineLayer.SILVER);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.ENRICHER)).hasValue(PipelineLayer.SILVER);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.VALIDATOR)).hasValue(PipelineLayer.SILVER);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.TRANSFORMER)).hasValue(PipelineLayer.GOLD);
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.SINK)).hasValue(PipelineLayer.GOLD);
    }

    @Test
    void layerOf_returnsEmptyForFaultNodes() {
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.AI_REVIEW)).isEmpty();
        assertThat(PipelineNodeTypes.layerOf(PipelineNodeTypes.HUMAN_REVIEW)).isEmpty();
    }

    @Test
    void layerOf_returnsEmptyForUnknownType() {
        assertThat(PipelineNodeTypes.layerOf(new NodeType("custom-widget"))).isEmpty();
    }

    // --- #31: Medallion layer enforcement ---

    @Test
    void layerConstraint_acceptsValidPipeline() {
        PipelineBlueprint blueprint = standardBlueprint();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        MedallionLayerConstraint.validate(graph);
    }

    @Test
    void layerConstraint_rejectsBackwardDependency() {
        // Bronze node depending on a Gold node — backward
        DesiredNode source = new DesiredNode(NodeId.of("raw-source"),
            PipelineNodeTypes.DATA_SOURCE,
            new DataSourceSpec("raw", "json", "kafka://raw"), false);
        DesiredNode transformer = new DesiredNode(NodeId.of("session-agg"),
            PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("sessionize"), List.of("group-by-session"), "parquet"), false);

        // source depends on transformer — backward (Bronze depends on Gold)
        DesiredStateGraph graph = factory.of(
            List.of(source, transformer),
            List.of(new Dependency(NodeId.of("raw-source"), NodeId.of("session-agg"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MedallionLayerConstraint.validate(graph))
            .isInstanceOf(MedallionLayerConstraint.LayerViolationException.class)
            .hasMessageContaining("raw-source")
            .hasMessageContaining("BRONZE")
            .hasMessageContaining("GOLD");
    }

    @Test
    void layerConstraint_rejectsLayerSkip() {
        // Gold node depending directly on Bronze (skipping Silver)
        DesiredNode source = new DesiredNode(NodeId.of("clickstream"),
            PipelineNodeTypes.DATA_SOURCE,
            new DataSourceSpec("clickstream", "json", "kafka://clicks"), false);
        DesiredNode transformer = new DesiredNode(NodeId.of("session-agg"),
            PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("sessionize"), List.of("group-by-session"), "parquet"), false);

        // transformer depends on source — Gold depends on Bronze (skips Silver)
        DesiredStateGraph graph = factory.of(
            List.of(source, transformer),
            List.of(new Dependency(NodeId.of("session-agg"), NodeId.of("clickstream"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MedallionLayerConstraint.validate(graph))
            .isInstanceOf(MedallionLayerConstraint.LayerViolationException.class)
            .hasMessageContaining("session-agg")
            .hasMessageContaining("GOLD")
            .hasMessageContaining("BRONZE");
    }

    @Test
    void layerConstraint_allowsSameLayerDependencies() {
        DesiredNode source1 = new DesiredNode(NodeId.of("source-a"),
            PipelineNodeTypes.DATA_SOURCE,
            new DataSourceSpec("a", "json", "kafka://a"), false);
        DesiredNode source2 = new DesiredNode(NodeId.of("source-b"),
            PipelineNodeTypes.DATA_SOURCE,
            new DataSourceSpec("b", "json", "kafka://b"), false);

        // source-b depends on source-a — same layer (Bronze → Bronze)
        DesiredStateGraph graph = factory.of(
            List.of(source1, source2),
            List.of(new Dependency(NodeId.of("source-b"), NodeId.of("source-a"))));

        MedallionLayerConstraint.validate(graph);
    }

    @Test
    void layerConstraint_ignoresFaultNodes() {
        // AI_REVIEW node depending on a Gold node — no layer, should be ignored
        DesiredNode transformer = new DesiredNode(NodeId.of("session-agg"),
            PipelineNodeTypes.TRANSFORMER,
            new TransformerSpec(List.of("sessionize"), List.of("group-by-session"), "parquet"), false);
        DesiredNode aiReview = new DesiredNode(NodeId.of("ai-review-session-agg"),
            PipelineNodeTypes.AI_REVIEW,
            new AiReviewSpec(NodeId.of("session-agg"), "provision failure"), false);

        DesiredStateGraph graph = factory.of(
            List.of(transformer, aiReview),
            List.of(new Dependency(NodeId.of("ai-review-session-agg"), NodeId.of("session-agg"))));

        MedallionLayerConstraint.validate(graph);
    }

    @Test
    void schemaDrift_approvalRestoresPipeline() {
        // Register schema and dependent stages
        world.registerSchema("click-schema",
            new PipelineWorld.SchemaDefinition("click-schema", List.of("userId", "pageUrl"), 1));

        world.setStage(NodeId.of("click-clean"),
            new PipelineWorld.StageEntry(PipelineNodeTypes.CLEANSER, PipelineWorld.StageState.DEGRADED,
                "click-schema", null, 100, 0, 0, null));
        world.setStage(NodeId.of("quality-gate"),
            new PipelineWorld.StageEntry(PipelineNodeTypes.VALIDATOR, PipelineWorld.StageState.DEGRADED,
                "click-schema", null, 50, 0, 0, null));

        // Approve schema change with new version
        world.approveSchemaChange("click-schema", 2);

        // Assert stages return to RUNNING
        assertThat(world.stageState(NodeId.of("click-clean"))).isEqualTo(PipelineWorld.StageState.RUNNING);
        assertThat(world.stageState(NodeId.of("quality-gate"))).isEqualTo(PipelineWorld.StageState.RUNNING);

        // Assert schema version updated
        assertThat(world.schema("click-schema").version()).isEqualTo(2);
    }

    // --- AgentProvider integration tests ---

    @Test
    void aiReview_invokesAgentProvider_resolved() {
        AgentProvider resolvingAgent = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().item(
                        new AgentEvent.TextDelta(
                                "Analysis complete. The issue is a transient connectivity error. RESOLVED."));
            }

            @Override
            public AgentSession openSession(AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };

        PipelineProvisioner agentProvisioner = new PipelineProvisioner(world, resolvingAgent);

        // Create an AI_REVIEW node without pre-setting the outcome
        NodeId targetNode = NodeId.of("ingest");
        NodeId reviewNode = NodeId.of("ai-review-ingest");
        DesiredNode aiReview = new DesiredNode(reviewNode, PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(targetNode, "source unavailable"), false);
        DesiredStateGraph graph = factory.of(List.of(aiReview), List.of());

        // Provision the AI_REVIEW node
        ProvisionResult result = agentProvisioner.provision(aiReview, new ProvisionContext("default", graph));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        // The agent resolved it — review should be RESOLVED
        PipelineWorld.ReviewEntry review = world.review(reviewNode);
        assertThat(review).isNotNull();
        assertThat(review.state()).isEqualTo(PipelineWorld.ReviewState.RESOLVED);
    }

    @Test
    void aiReview_invokesAgentProvider_unresolved() {
        AgentProvider unresolvedAgent = new AgentProvider() {
            @Override
            public Multi<AgentEvent> invoke(AgentSessionConfig config) {
                return Multi.createFrom().item(
                        new AgentEvent.TextDelta(
                                "This requires manual intervention. Cannot determine root cause."));
            }

            @Override
            public AgentSession openSession(AgentSessionInit init) {
                throw new UnsupportedOperationException();
            }
        };

        PipelineProvisioner agentProvisioner = new PipelineProvisioner(world, unresolvedAgent);

        NodeId targetNode = NodeId.of("ingest");
        NodeId reviewNode = NodeId.of("ai-review-ingest");
        DesiredNode aiReview = new DesiredNode(reviewNode, PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(targetNode, "source unavailable"), false);
        DesiredStateGraph graph = factory.of(List.of(aiReview), List.of());

        ProvisionResult result = agentProvisioner.provision(aiReview, new ProvisionContext("default", graph));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        PipelineWorld.ReviewEntry review = world.review(reviewNode);
        assertThat(review).isNotNull();
        assertThat(review.state()).isEqualTo(PipelineWorld.ReviewState.UNRESOLVED);
    }

    @Test
    void aiReview_noOpAgent_registersPending() {
        PipelineProvisioner noOpProvisioner = new PipelineProvisioner(world, new NoOpAgentProvider());

        NodeId targetNode = NodeId.of("ingest");
        NodeId reviewNode = NodeId.of("ai-review-ingest");
        DesiredNode aiReview = new DesiredNode(reviewNode, PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(targetNode, "source unavailable"), false);
        DesiredStateGraph graph = factory.of(List.of(aiReview), List.of());

        ProvisionResult result = noOpProvisioner.provision(aiReview, new ProvisionContext("default", graph));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);

        PipelineWorld.ReviewEntry review = world.review(reviewNode);
        assertThat(review).isNotNull();
        assertThat(review.state()).isEqualTo(PipelineWorld.ReviewState.PENDING);
    }
}
