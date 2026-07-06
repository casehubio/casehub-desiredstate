package io.casehub.desiredstate.example.spatial.defense;

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

class DefensePostureTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;
    private BattlefieldActualStateAdapter adapter;
    private BattlefieldProvisioner provisioner;
    private DefaultDesiredStateGraphFactory factory;
    private TransitionPlanner planner;
    private DefenseGoalCompiler compiler;
    private GridRenderer renderer;

    @BeforeEach
    void setUp() {
        // Grid with height variation: ridge at row 3, ramp at (3,5)
        grid = TerrainGrid.create(10, 10,
            (r, c) -> (r == 3 && c != 5) ? 2 : (r == 3 && c == 5) ? 1 : 0,
            (r, c) -> (r == 3 && c != 5) ? TerrainType.CLIFF :
                      (r == 3 && c == 5) ? TerrainType.RAMP : TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
        adapter = new BattlefieldActualStateAdapter(world);
        provisioner = new BattlefieldProvisioner(world);
        factory = new DefaultDesiredStateGraphFactory();
        planner = new TransitionPlanner();
        compiler = new DefenseGoalCompiler();
        renderer = new GridRenderer(world, 0);
    }

    @Test
    void initialDeployment_compilesAndProvisions() {
        var blueprint = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .scout(1, 1)
            .zone("north-perimeter",
                Map.of("cell-2-0", 0.4, "cell-2-1", 0.3, "cell-2-2", 0.3), 100)
            .build();

        CompilationResult result = compiler.compile(blueprint, factory);


        var graph = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).isNotEmpty();
        assertThat(graph.roots()).isNotEmpty();

        var actual = adapter.readActual(graph, "test");
        var plan = planner.plan(graph, actual);

        assertThat(plan.additions()).isNotEmpty();

        for (var step : plan.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }

        renderer.printFrame("Initial deployment");
        assertThat(world.isRevealed(0, 0)).isTrue();
    }

    @Test
    void scoutRevealsTerrainTriggersRecompile() {
        var blueprint1 = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .scout(2, 4)
            .zone("perimeter", Map.of("cell-1-0", 0.5, "cell-1-1", 0.5), 50)
            .build();
        CompilationResult result1 = compiler.compile(blueprint1, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        var actual1 = adapter.readActual(graph1, "test");
        var plan1 = planner.plan(graph1, actual1);
        for (var step : plan1.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph1));
        }
        renderer.printFrame("Before scout");

        // Scout revealed new terrain — recompile with new zone cells
        var blueprint2 = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .scout(2, 4)
            .zone("perimeter",
                Map.of("cell-1-0", 0.3, "cell-1-1", 0.3,
                       "cell-2-3", 0.2, "cell-2-4", 0.2), 50)
            .build();
        CompilationResult result2 = compiler.compile(blueprint2, factory);

        var graph2 = ((CompilationResult.SingleGraph) result2).graph();
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // New cells and units added
        assertThat(plan2.additions()).isNotEmpty();
        for (var step : plan2.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph2));
        }
        renderer.printFrame("After scout reveals");
    }

    @Test
    void unitLossTriggersRestoration() {
        var blueprint = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .zone("perimeter",
                Map.of("cell-1-0", 0.5, "cell-1-1", 0.5), 100)
            .build();
        CompilationResult result = compiler.compile(blueprint, factory);

        var graph = ((CompilationResult.SingleGraph) result).graph();
        var actual1 = adapter.readActual(graph, "test");
        var plan1 = planner.plan(graph, actual1);
        for (var step : plan1.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }

        // Inject: destroy unit
        world.removeUnit(NodeId.of("unit-cell-1-0"));

        // Re-read actual → planner should restore the unit with original specs
        var actual2 = adapter.readActual(graph, "test");
        assertThat(actual2.statusOf(NodeId.of("unit-cell-1-0")))
            .hasValue(NodeStatus.ABSENT);

        var plan2 = planner.plan(graph, actual2);
        assertThat(plan2.additions()).anyMatch(step ->
            step.node().id().equals(NodeId.of("unit-cell-1-0")));

        for (var step : plan2.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }
        assertThat(world.isUnitPlaced(NodeId.of("unit-cell-1-0"))).isTrue();
        assertThat(world.unitStrength(NodeId.of("unit-cell-1-0"))).isEqualTo(50);
        renderer.printFrame("After restoration");
    }

    @Test
    void threatSpottedShiftsPriorities() {
        var blueprint1 = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .zone("perimeter",
                Map.of("cell-1-0", 0.5, "cell-1-1", 0.5), 100)
            .build();
        CompilationResult result1 = compiler.compile(blueprint1, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        var actual1 = adapter.readActual(graph1, "test");
        var plan1 = planner.plan(graph1, actual1);
        for (var step : plan1.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph1));
        }

        // Threat spotted → recompile with shifted ratios
        var blueprint2 = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .zone("perimeter",
                Map.of("cell-1-0", 0.8, "cell-1-1", 0.2), 100)
            .build();
        CompilationResult result2 = compiler.compile(blueprint2, factory);

        var graph2 = ((CompilationResult.SingleGraph) result2).graph();
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Units should be drifted — strength changed
        assertThat(plan2.additions()).isNotEmpty();
        for (var step : plan2.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph2));
        }
        assertThat(world.unitStrength(NodeId.of("unit-cell-1-0"))).isEqualTo(80);
        assertThat(world.unitStrength(NodeId.of("unit-cell-1-1"))).isEqualTo(20);
        renderer.printFrame("After threat shifts");
    }

    @Test
    void terrainChangeForcesReallocation() {
        var blueprint1 = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .zone("perimeter",
                Map.of("cell-2-4", 0.3, "cell-2-5", 0.4, "cell-2-6", 0.3), 100)
            .build();
        CompilationResult result1 = compiler.compile(blueprint1, factory);

        var graph1 = ((CompilationResult.SingleGraph) result1).graph();
        var actual1 = adapter.readActual(graph1, "test");
        var plan1 = planner.plan(graph1, actual1);
        for (var step : plan1.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph1));
        }

        // Ramp at (2,5) collapses → remove that cell from zone, redistribute
        var blueprint2 = DefenseBlueprint.builder()
            .basePosition(0, 0)
            .zone("perimeter",
                Map.of("cell-2-4", 0.5, "cell-2-6", 0.5), 100)
            .build();
        CompilationResult result2 = compiler.compile(blueprint2, factory);

        var graph2 = ((CompilationResult.SingleGraph) result2).graph();
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        // Old unit at (2,5) should be orphaned → deprovisioned
        assertThat(plan2.removals()).anyMatch(step ->
            step.node().id().equals(NodeId.of("unit-cell-2-5")));

        for (var step : plan2.removals()) {
            provisioner.deprovision(step.node(), new DeprovisionContext("test", graph1));
        }
        for (var step : plan2.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph2));
        }
        assertThat(world.isUnitPlaced(NodeId.of("unit-cell-2-5"))).isFalse();
        assertThat(world.unitStrength(NodeId.of("unit-cell-2-4"))).isEqualTo(50);
        renderer.printFrame("After terrain change");
    }
}
