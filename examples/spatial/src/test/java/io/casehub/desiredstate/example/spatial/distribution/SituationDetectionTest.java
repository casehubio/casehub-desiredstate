package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.*;
import io.casehub.desiredstate.example.spatial.world.*;
import io.casehub.desiredstate.ras.NodeFaultGanglion;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.ReconciliationEventEmitter;
import io.casehub.ras.api.*;
import io.casehub.ras.persistence.memory.InMemorySituationStore;
import io.casehub.ras.runtime.DefaultRasTriggerPolicy;
import io.casehub.ras.runtime.SituationEvaluator;
import io.casehub.ras.runtime.TestSituationDefinitionRegistry;
import io.casehub.ras.testing.MockCaseTrigger;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end proof of the RAS detection pipeline using spatial POC domain types.
 * <p>
 * Wires RAS components directly (no CDI, no full runtime) to prove that repeated
 * node faults trigger situation detection → CREATE_CASE decision.
 * <p>
 * Scenario: frontier zone with 2 cells, same unit destroyed 3 times across 3 cycles.
 * After 3 faults, ChainMode.Count(NodeFaultGanglion.ID, 3) triggers a replan case.
 */
class SituationDetectionTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;
    private BattlefieldActualStateAdapter adapter;
    private DefaultDesiredStateGraphFactory factory;
    private DistributionGoalCompiler compiler;

    private ReconciliationEventEmitter emitter;
    private InMemorySituationStore situationStore;
    private MockCaseTrigger caseTrigger;
    private DefaultRasTriggerPolicy triggerPolicy;
    private SituationEvaluator evaluator;
    private NodeFaultGanglion ganglion;

    private static final String TENANCY_ID = "test-tenant";
    private static final Instant T1 = Instant.parse("2026-07-05T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-05T10:01:00Z");
    private static final Instant T3 = Instant.parse("2026-07-05T10:02:00Z");

    @BeforeEach
    void setUp() {
        // Spatial domain setup
        grid = TerrainGrid.create(10, 10,
            (r, c) -> (r >= 4 && r <= 6 && c >= 3 && c <= 7) ? 1 : 0,
            (r, c) -> TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
        adapter = new BattlefieldActualStateAdapter(world);
        factory = new DefaultDesiredStateGraphFactory();
        compiler = new DistributionGoalCompiler();

        // RAS pipeline components
        emitter = new ReconciliationEventEmitter();
        situationStore = new InMemorySituationStore();
        caseTrigger = new MockCaseTrigger();
        triggerPolicy = new DefaultRasTriggerPolicy();
        ganglion = new NodeFaultGanglion();

        // Situation definition: 3 consecutive node faults within 10 minutes
        var triggerConfig = new CaseTriggerConfig(
            "desiredstate", "replan", "1.0",
            Map.of("reason", "repeated-fault-pattern-detected"));

        var situationDef = new SituationDefinition(
            "repeated-node-fault",
            Set.of(DesiredStateEventTypes.NODE_FAULTED, DesiredStateEventTypes.NODE_RECOVERED),
            Duration.ofMinutes(10), // correlation window
            null, // no buffer delay
            new ChainMode.Count(NodeFaultGanglion.ID, 3), // trigger after 3 detections
            triggerConfig,
            null // single-fire mode
        );

        var providers = List.<SituationDefinitionProvider>of(
            () -> List.of(new SituationRegistration(situationDef))
        );
        var ganglia = List.<Ganglion>of(ganglion);
        var registry = TestSituationDefinitionRegistry.create(providers, ganglia);

        // Wire SituationEvaluator with all dependencies
        evaluator = new SituationEvaluator(
            situationStore,
            triggerPolicy,
            caseTrigger,
            registry,
            3, // max retries
            new TestChangeEvent()
        );
    }

    @Test
    void threeFaultsOnSameNodeTriggersSituation() {
        // Setup: frontier zone with 2 cells
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        CompilationResult result = compiler.compile(blueprint, factory);

        var graph = ((CompilationResult.SingleGraph) result).graph();

        // Provision initial state
        var provisioner = new BattlefieldProvisioner(world);
        var actual = adapter.readActual(graph, TENANCY_ID);
        for (var node : graph.nodes().values()) {
            provisioner.provision(node, new ProvisionContext(TENANCY_ID, graph));
        }

        var targetNodeId = NodeId.of("unit-cell-4-0");

        // Cycle 1: destroy unit, emit node.faulted event, feed to RAS
        world.removeUnit(targetNodeId);
        CloudEvent fault1 = buildNodeFaultedEvent(targetNodeId, "unit", T1);
        evaluator.evaluate(fault1, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        assertThat(caseTrigger.firedCases())
            .as("After 1 fault, no case triggered yet")
            .isEmpty();
        assertThat(situationStore.find("repeated-node-fault", targetNodeId.value(), TENANCY_ID)
            .await().indefinitely())
            .as("Situation context saved with 1 detection")
            .isPresent()
            .get()
            .satisfies(ctx -> assertThat(ctx.detections()).hasSize(1));

        // Cycle 2: destroy unit again (after reconciliation restored it)
        provisioner.provision(
            graph.nodes().get(targetNodeId),
            new ProvisionContext(TENANCY_ID, graph)
        );
        world.removeUnit(targetNodeId);
        CloudEvent fault2 = buildNodeFaultedEvent(targetNodeId, "unit", T2);
        evaluator.evaluate(fault2, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        assertThat(caseTrigger.firedCases())
            .as("After 2 faults, no case triggered yet")
            .isEmpty();
        assertThat(situationStore.find("repeated-node-fault", targetNodeId.value(), TENANCY_ID)
            .await().indefinitely())
            .as("Situation context has 2 detections")
            .isPresent()
            .get()
            .satisfies(ctx -> assertThat(ctx.detections()).hasSize(2));

        // Cycle 3: destroy unit again
        provisioner.provision(
            graph.nodes().get(targetNodeId),
            new ProvisionContext(TENANCY_ID, graph)
        );
        world.removeUnit(targetNodeId);
        CloudEvent fault3 = buildNodeFaultedEvent(targetNodeId, "unit", T3);
        evaluator.evaluate(fault3, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        // Assert: case triggered after 3rd fault
        assertThat(caseTrigger.firedCases())
            .as("After 3 faults, case triggered")
            .hasSize(1);

        var firedCase = caseTrigger.firedCases().get(0);
        assertThat(firedCase.triggerConfig().caseNamespace()).isEqualTo("desiredstate");
        assertThat(firedCase.triggerConfig().caseName()).isEqualTo("replan");
        assertThat(firedCase.context().detections())
            .as("Fired case context contains 3 detections")
            .hasSize(3);
        assertThat(firedCase.context().correlationKey()).isEqualTo(targetNodeId.value());
    }

    @Test
    void countModeAccumulatesAcrossRecovery() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        CompilationResult result = compiler.compile(blueprint, factory);

        var graph = ((CompilationResult.SingleGraph) result).graph();

        var provisioner = new BattlefieldProvisioner(world);
        for (var node : graph.nodes().values()) {
            provisioner.provision(node, new ProvisionContext(TENANCY_ID, graph));
        }

        var targetNodeId = NodeId.of("unit-cell-4-0");

        // 2 faults
        world.removeUnit(targetNodeId);
        CloudEvent fault1 = buildNodeFaultedEvent(targetNodeId, "unit", T1);
        evaluator.evaluate(fault1, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        provisioner.provision(
            graph.nodes().get(targetNodeId),
            new ProvisionContext(TENANCY_ID, graph)
        );
        world.removeUnit(targetNodeId);
        CloudEvent fault2 = buildNodeFaultedEvent(targetNodeId, "unit", T2);
        evaluator.evaluate(fault2, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        // 1 recovery (ANTI signal)
        provisioner.provision(
            graph.nodes().get(targetNodeId),
            new ProvisionContext(TENANCY_ID, graph)
        );
        CloudEvent recovery = buildNodeRecoveredEvent(targetNodeId, T2.plusSeconds(30));
        evaluator.evaluate(recovery, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        // 1 more fault
        world.removeUnit(targetNodeId);
        CloudEvent fault3 = buildNodeFaultedEvent(targetNodeId, "unit", T3);
        evaluator.evaluate(fault3, getSituationDef(), targetNodeId.value(), TENANCY_ID);

        // With Count mode: total qualifying signals = 3 (2 faults + 1 ANTI + 1 fault)
        // Count accumulates all DETECTED signals regardless of ANTI signals in between
        // (Streak would reset on ANTI, but Count does not)
        assertThat(caseTrigger.firedCases())
            .as("Count mode: 3 total DETECTED signals (faults) triggers case")
            .hasSize(1);

        var ctx = situationStore.find("repeated-node-fault", targetNodeId.value(), TENANCY_ID)
            .await().indefinitely();
        assertThat(ctx)
            .as("Situation context contains all signals including ANTI")
            .isPresent()
            .get()
            .satisfies(situation -> {
                assertThat(situation.detections())
                    .as("4 total detections: 2 faults + 1 recovery (ANTI) + 1 fault")
                    .hasSize(4);
                assertThat(situation.detections().stream()
                    .filter(d -> d.result().signal() == DetectionSignal.DETECTED)
                    .count())
                    .as("3 DETECTED signals")
                    .isEqualTo(3);
            });
    }

    private CloudEvent buildNodeFaultedEvent(NodeId nodeId, String nodeType, Instant time) {
        var data = new NodeFaultedData(
            TENANCY_ID,
            nodeId.value(),
            nodeType,
            FaultType.NODE_DESTROYED.name(),
            "Unit destroyed in combat",
            1L,
            null
        );
        var event = emitter.nodeFaulted(data);
        // CloudEvent time is immutable, so we rebuild with the desired time
        return io.cloudevents.core.builder.CloudEventBuilder.v1(event)
            .withTime(time.atOffset(java.time.ZoneOffset.UTC))
            .build();
    }

    private CloudEvent buildNodeRecoveredEvent(NodeId nodeId, Instant time) {
        var data = new NodeRecoveredData(
            TENANCY_ID,
            nodeId.value(),
            "unit",
            1L,
            null
        );
        var event = emitter.nodeRecovered(data);
        return io.cloudevents.core.builder.CloudEventBuilder.v1(event)
            .withTime(time.atOffset(java.time.ZoneOffset.UTC))
            .build();
    }

    private SituationDefinition getSituationDef() {
        return new SituationDefinition(
            "repeated-node-fault",
            Set.of(DesiredStateEventTypes.NODE_FAULTED, DesiredStateEventTypes.NODE_RECOVERED),
            Duration.ofMinutes(10),
            null,
            new ChainMode.Count(NodeFaultGanglion.ID, 3),
            new CaseTriggerConfig(
                "desiredstate", "replan", "1.0",
                Map.of("reason", "repeated-fault-pattern-detected")),
            null
        );
    }

    private static class TestChangeEvent implements Event<SituationChangeEvent> {
        private final CopyOnWriteArrayList<SituationChangeEvent> fired = new CopyOnWriteArrayList<>();

        @Override
        public void fire(SituationChangeEvent event) {
            fired.add(event);
        }

        @Override
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends SituationChangeEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            fired.add(event);
            return CompletableFuture.completedFuture(event);
        }

        @Override public Event<SituationChangeEvent> select(Annotation... qualifiers) { return this; }
        @Override public <U extends SituationChangeEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
        @Override public <U extends SituationChangeEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    }
}
