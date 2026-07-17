package io.casehub.desiredstate.example.spatial.attack;

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

class AttackWaypointsTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;
    private BattlefieldActualStateAdapter adapter;
    private BattlefieldProvisioner provisioner;
    private DefaultDesiredStateGraphFactory factory;
    private TransitionPlanner planner;
    private AttackGoalCompiler compiler;
    private GridRenderer renderer;

    @BeforeEach
    void setUp() {
        // Ridge at row 4, ramp at (4,6)
        grid = TerrainGrid.create(10, 10,
            (r, c) -> (r == 4 && c != 6) ? 2 : (r == 4 && c == 6) ? 1 : 0,
            (r, c) -> (r == 4 && c != 6) ? TerrainType.CLIFF :
                      (r == 4 && c == 6) ? TerrainType.RAMP : TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
        adapter = new BattlefieldActualStateAdapter(world);
        provisioner = new BattlefieldProvisioner(world);
        factory = new DefaultDesiredStateGraphFactory();
        planner = new TransitionPlanner();
        compiler = new AttackGoalCompiler();
        renderer = new GridRenderer(world, 0);
    }

    private void executeAdditions(TransitionPlan plan, DesiredStateGraph graph) {
        for (var step : plan.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }
    }

    private void executeRemovals(TransitionPlan plan, DesiredStateGraph beforeGraph) {
        for (var step : plan.removals()) {
            provisioner.deprovision(step.node(), new DeprovisionContext("test", beforeGraph));
        }
    }

    @Test
    void initialScoutAndWaypointChain() {
        var blueprint = AttackBlueprint.builder()
            .origin(0, 0)
            .scout(2, 2)
            .waypoint(1, 1, 30)
            .waypoint(2, 2, 25)
            .waypoint(3, 3, 20)
            .totalForce(75)
            .build();

        CompilationResult result = compiler.compile(blueprint, factory);


        var graph = ((CompilationResult.SingleGraph) result).graph();

        // Verify dependency chain: wp3 → wp2 → wp1
        var wp1 = NodeId.of("cell-1-1");
        var wp2 = NodeId.of("cell-2-2");
        var wp3 = NodeId.of("cell-3-3");
        assertThat(graph.dependenciesOf(wp2)).contains(wp1);
        assertThat(graph.dependenciesOf(wp3)).contains(wp2);

        var actual = adapter.readActual(graph, "test");
        var plan = planner.plan(graph, actual);

        // Topological order: cells first, then wp1, wp2, wp3
        var additionIds = plan.additions().stream()
            .map(s -> s.node().id()).toList();
        assertThat(additionIds.indexOf(wp1)).isLessThan(additionIds.indexOf(wp2));
        assertThat(additionIds.indexOf(wp2)).isLessThan(additionIds.indexOf(wp3));

        executeAdditions(plan, graph);
        renderer.printFrame("Initial attack path");
    }

    @Test
    void pathRerouteAfterTerrainCollapse() {
        // Initial path through ramp at (4,6)
        var blueprint1 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(2, 4, 30)
            .waypoint(3, 5, 25)
            .waypoint(4, 6, 20) // through ramp
            .waypoint(5, 7, 25)
            .totalForce(100)
            .build();
        CompilationResult result1 = compiler.compile(blueprint1, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        var actual1 = adapter.readActual(graph1, "test");
        var plan1 = planner.plan(graph1, actual1);
        executeAdditions(plan1, graph1);
        renderer.printFrame("Before ramp collapse");

        // Ramp collapses → reroute around ridge
        var blueprint2 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(2, 8, 30)
            .waypoint(3, 9, 25)
            .waypoint(5, 9, 20)
            .waypoint(5, 7, 25)
            .totalForce(100)
            .build();
        CompilationResult result2 = compiler.compile(blueprint2, factory);

        var graph2 = ((CompilationResult.SingleGraph) result2).graph();
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Old waypoints orphaned
        assertThat(plan2.removals()).isNotEmpty();
        // New waypoints added
        assertThat(plan2.additions()).isNotEmpty();

        executeRemovals(plan2, graph1);
        executeAdditions(plan2, graph2);
        renderer.printFrame("After reroute");
    }

    @Test
    void lossesAtWaypointTriggerRebalance_viaRecompilation() {
        var blueprint1 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(1, 1, 40)
            .waypoint(2, 2, 30)
            .waypoint(3, 3, 30)
            .totalForce(100)
            .build();
        CompilationResult result1 = compiler.compile(blueprint1, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        executeAdditions(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1);

        // Losses at waypoint (2,2) → recompile with more force there
        var blueprint2 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(1, 1, 25)
            .waypoint(2, 2, 50) // reinforced
            .waypoint(3, 3, 25)
            .totalForce(100)
            .build();
        CompilationResult result2 = compiler.compile(blueprint2, factory);

        var graph2 = ((CompilationResult.SingleGraph) result2).graph();
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        executeRemovals(plan2, graph1);
        executeAdditions(plan2, graph2);
        assertThat(world.unitStrength(NodeId.of("unit-waypoint-2-2"))).isEqualTo(50);
        renderer.printFrame("After reinforcement");
    }

    @Test
    void lossesAtWaypointTriggerRebalance_viaIncrementalMutation() {
        var blueprint = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(1, 1, 40)
            .waypoint(2, 2, 30)
            .waypoint(3, 3, 30)
            .totalForce(100)
            .build();
        CompilationResult result1 = compiler.compile(blueprint, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        executeAdditions(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1);

        // Incremental mutation: update waypoint (2,2) strength to 50
        var wp2Id = NodeId.of("cell-2-2");
        var unitId = NodeId.of("unit-waypoint-2-2");
        var graph2 = graph1
            .withMutation(new GraphMutation.UpdateNode(unitId, new DesiredNode(unitId, SpatialNodeTypes.UNIT, new UnitSpec(wp2Id, 50), HumanGating.NONE)));

        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Only the mutated unit should need re-provisioning (DRIFTED)
        assertThat(plan2.additions()).hasSize(1);
        assertThat(plan2.additions().get(0).node().id()).isEqualTo(unitId);

        executeAdditions(plan2, graph2);
        assertThat(world.unitStrength(unitId)).isEqualTo(50);
        renderer.printFrame("After incremental mutation");
    }

    @Test
    void highGroundDiscoveryChangesAllocation() {
        var blueprint1 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(2, 2, 30)
            .waypoint(3, 3, 30)
            .waypoint(5, 5, 40)
            .totalForce(100)
            .build();
        CompilationResult result1 = compiler.compile(blueprint1, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        executeAdditions(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1);

        // High ground at (7,8) revealed as occupied → need more force at (5,5)
        var blueprint2 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(2, 2, 20)
            .waypoint(3, 3, 20)
            .waypoint(5, 5, 60) // hardened
            .totalForce(100)
            .build();
        CompilationResult result2 = compiler.compile(blueprint2, factory);

        var graph2 = ((CompilationResult.SingleGraph) result2).graph();
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        executeRemovals(plan2, graph1);
        executeAdditions(plan2, graph2);
        assertThat(world.unitStrength(NodeId.of("unit-waypoint-5-5"))).isEqualTo(60);
        renderer.printFrame("After high ground discovery");
    }
}
