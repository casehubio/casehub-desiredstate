package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.render.GridRenderer;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.*;
import io.casehub.desiredstate.example.spatial.world.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ForceDistributionTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;
    private BattlefieldActualStateAdapter adapter;
    private BattlefieldProvisioner provisioner;
    private DefaultDesiredStateGraphFactory factory;
    private TransitionPlanner planner;
    private DistributionGoalCompiler compiler;
    private GridRenderer renderer;

    @BeforeEach
    void setUp() {
        grid = TerrainGrid.create(10, 10,
            (r, c) -> (r >= 4 && r <= 6 && c >= 3 && c <= 7) ? 1 : 0,
            (r, c) -> TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
        adapter = new BattlefieldActualStateAdapter(world);
        provisioner = new BattlefieldProvisioner(world);
        factory = new DefaultDesiredStateGraphFactory();
        planner = new TransitionPlanner();
        compiler = new DistributionGoalCompiler();
        renderer = new GridRenderer(world, 0);
    }

    private void executeAll(TransitionPlan plan, DesiredStateGraph graph,
                           DesiredStateGraph beforeGraph) {
        for (var step : plan.removals()) {
            provisioner.deprovision(step.node(), new DeprovisionContext("test", beforeGraph));
        }
        for (var step : plan.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }
    }

    // --- Layer 2: Ratio distribution ---

    @Test
    void initialFrontierAllocation() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.3)
            .frontierCell(4, 1, 0.2)
            .frontierCell(4, 2, 0.2)
            .frontierCell(4, 3, 0.1)
            .frontierCell(4, 4, 0.2)
            .totalForce(100)
            .zoneName("frontier")
            .build();

        var graph = compiler.compile(blueprint, factory);

        // Verify zone allocation
        var zoneNode = graph.nodes().get(NodeId.of("zone-frontier"));
        assertThat(zoneNode).isNotNull();
        var zoneSpec = (ZoneSpec) zoneNode.spec();
        assertThat(zoneSpec.totalForce()).isEqualTo(100);
        assertThat(zoneSpec.allocation()).hasSize(5);

        // Verify unit strengths match zone ratios
        var unit40 = graph.nodes().get(NodeId.of("unit-cell-4-0"));
        assertThat(((UnitSpec) unit40.spec()).strength()).isEqualTo(30); // 100 * 0.3

        var actual = adapter.readActual(graph, "test");
        var plan = planner.plan(graph, actual);
        executeAll(plan, graph, graph);

        assertThat(world.unitStrength(NodeId.of("unit-cell-4-0"))).isEqualTo(30);
        renderer.printFrame("Initial frontier");
    }

    @Test
    void frontierExpansionRedistributes() {
        // Initial frontier: 5 cells
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.3)
            .frontierCell(4, 1, 0.2)
            .frontierCell(4, 2, 0.2)
            .frontierCell(4, 3, 0.1)
            .frontierCell(4, 4, 0.2)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);

        // Frontier expands — 3 new cells discovered beyond
        var blueprint2 = DistributionBlueprint.builder()
            .frontierCell(5, 1, 0.15)
            .frontierCell(5, 2, 0.20)
            .frontierCell(5, 3, 0.15)
            .frontierCell(5, 4, 0.15)
            .frontierCell(5, 5, 0.10)
            .frontierCell(5, 6, 0.10)
            .frontierCell(5, 7, 0.10)
            .frontierCell(5, 8, 0.05)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph2 = compiler.compile(blueprint2, factory);
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Old units orphaned, new units provisioned
        assertThat(plan2.removals()).isNotEmpty();
        assertThat(plan2.additions()).isNotEmpty();

        executeAll(plan2, graph2, graph1);
        renderer.printFrame("After frontier expansion");
    }

    @Test
    void priorityShiftChangesAllocations_viaRecompilation() {
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);

        // Enemy fortification → double weight on (4,0)
        var blueprint2 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.8)
            .frontierCell(4, 1, 0.2)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph2 = compiler.compile(blueprint2, factory);
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        executeAll(plan2, graph2, graph1);
        assertThat(world.unitStrength(NodeId.of("unit-cell-4-0"))).isEqualTo(80);
        assertThat(world.unitStrength(NodeId.of("unit-cell-4-1"))).isEqualTo(20);
    }

    @Test
    void zoneSplitStructuralChange() {
        // Single zone covering all frontier cells
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.25)
            .frontierCell(4, 1, 0.25)
            .frontierCell(4, 8, 0.25)
            .frontierCell(4, 9, 0.25)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);

        // Split into north-flank and south-flank
        var blueprint2a = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(60)
            .zoneName("north-flank")
            .build();
        var blueprint2b = DistributionBlueprint.builder()
            .frontierCell(4, 8, 0.5)
            .frontierCell(4, 9, 0.5)
            .totalForce(40)
            .zoneName("south-flank")
            .build();
        var graphA = compiler.compile(blueprint2a, factory);
        var graphB = compiler.compile(blueprint2b, factory);
        var graph2 = graphA.overlay(graphB);

        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Old single zone deprovisioned, two new zones provisioned
        // This is a teardown-rebuild — expected finding #2
        assertThat(plan2.removals()).isNotEmpty();
        assertThat(plan2.additions()).isNotEmpty();

        executeAll(plan2, graph2, graph1);
        renderer.printFrame("After zone split");
    }

    // --- Layer 3: Fault policy coupling ---

    @Test
    void faultPolicyInformationGap() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph = compiler.compile(blueprint, factory);
        executeAll(planner.plan(graph, adapter.readActual(graph, "test")), graph, graph);

        // Inject: destroy unit at (4,0)
        world.removeUnit(NodeId.of("unit-cell-4-0"));

        // ActualStateAdapter reports zone as DRIFTED (member unit missing)
        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(NodeId.of("zone-frontier"))).hasValue(NodeStatus.DRIFTED);

        // Fault event for the zone
        var faultEvent = new FaultEvent(
            NodeId.of("zone-frontier"), FaultType.NODE_DEGRADED,
            "Zone member unit missing or strength mismatch");

        // ZoneRebalanceFaultPolicy attempts redistribution
        var policy = new ZoneRebalanceFaultPolicy();
        var mutations = policy.onFault(faultEvent, graph, actual);

        // FIXED: Policy now uses ActualState to identify ABSENT unit and redistribute
        assertThat(mutations).as(
            "Policy uses ActualState to determine unit-cell-4-0 is ABSENT. " +
            "Redistributes zone allocation among surviving units.")
            .isNotEmpty();

        // Zone spec updated with redistributed ratios
        assertThat(mutations).anyMatch(m ->
            m instanceof GraphMutation.UpdateNode u
            && u.id().equals(NodeId.of("zone-frontier")));

        // Surviving unit spec updated with new strength
        assertThat(mutations).anyMatch(m ->
            m instanceof GraphMutation.UpdateNode u
            && u.id().equals(NodeId.of("unit-cell-4-1")));
    }

    // --- Layer 4: Strategic pivot ---

    @Test
    void repeatedLossesUndetected() {
        var blueprint = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("frontier")
            .build();
        var graph = compiler.compile(blueprint, factory);
        executeAll(planner.plan(graph, adapter.readActual(graph, "test")), graph, graph);

        var policy = new ZoneRebalanceFaultPolicy();
        var allMutations = new ArrayList<List<GraphMutation>>();

        // 3 consecutive cycles of losses
        for (int cycle = 0; cycle < 3; cycle++) {
            world.removeUnit(NodeId.of("unit-cell-4-0"));
            var actual = adapter.readActual(graph, "test");
            var faultEvent = new FaultEvent(
                NodeId.of("zone-frontier"), FaultType.NODE_DEGRADED,
                "Cycle " + cycle + ": zone member destroyed");
            allMutations.add(policy.onFault(faultEvent, graph, actual));

            // Planner restores the unit
            var plan = planner.plan(graph, actual);
            executeAll(plan, graph, graph);
        }

        // After the fix: policy CAN redistribute (it has ActualState),
        // so it returns non-empty mutations each cycle.
        // FINDING: Each cycle is still handled independently. No mechanism detects
        // the pattern "we keep losing units at this position."
        // The fault policy has no memory, no cycle count, no aggregate view.
        // Finding #4: no aggregate subgraph evaluation.
        // Finding #6: no correlated fault detection.
        assertThat(allMutations).as(
            "Three consecutive losses at the same position. " +
            "Policy can now redistribute (returns non-empty mutations), " +
            "but each cycle is still handled independently — no pattern detection, no escalation. " +
            "Findings #4 (no aggregate evaluation) and #6 (no correlated faults).")
            .allSatisfy(mutations -> assertThat(mutations).isNotEmpty());
    }

    @Test
    void strategicPivotHasHomeInRasSituationDetection() {
        var blueprint1 = DistributionBlueprint.builder()
            .frontierCell(4, 0, 0.5)
            .frontierCell(4, 1, 0.5)
            .totalForce(100)
            .zoneName("north-approach")
            .build();
        var graph1 = compiler.compile(blueprint1, factory);
        executeAll(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1, graph1);
        renderer.printFrame("North approach deployed");

        // Enemy has fortified heavily — the north approach is failing.
        // Decision: pivot to south approach.

        // The GoalCompiler CAN produce the new graph:
        var blueprint2 = DistributionBlueprint.builder()
            .frontierCell(7, 0, 0.5)
            .frontierCell(7, 1, 0.5)
            .totalForce(100)
            .zoneName("south-approach")
            .build();
        var graph2 = compiler.compile(blueprint2, factory);

        // The reconciliation loop CAN transition between them:
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);
        executeAll(plan2, graph2, graph1);

        // The runtime executes the transition. The DECISION to pivot now has a home:
        // RAS situation detection observes repeated failures via CloudEvents,
        // NodeFaultGanglion detects the pattern, and CaseTrigger fires a replan case.
        // The test verifies the graph transition; the detection pipeline is proven
        // in SituationDetectionTest.
        assertThat(graph2.nodes().keySet()).as(
            "Graph transitioned from north to south approach. " +
            "The decision to pivot is handled by RAS: NodeFaultGanglion + " +
            "ChainMode.Count → CaseTrigger → replan case.")
            .noneMatch(id -> id.value().contains("north"));
        renderer.printFrame("After strategic pivot to south");
    }
}
