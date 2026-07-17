package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BattlefieldProvisionerTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;
    private BattlefieldProvisioner provisioner;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
        provisioner = new BattlefieldProvisioner(world);
        factory = new DefaultDesiredStateGraphFactory();
    }

    @Test
    void handledTypes_allFour() {
        assertThat(provisioner.handledTypes()).containsExactlyInAnyOrder(
            SpatialNodeTypes.CELL, SpatialNodeTypes.UNIT,
            SpatialNodeTypes.SCOUT, SpatialNodeTypes.ZONE);
    }

    @Test
    void provisionCell_revealsInWorld() {
        var cellNode = new DesiredNode(NodeId.of("cell-5-5"), SpatialNodeTypes.CELL,
            new CellSpec(5, 5, 0, TerrainType.OPEN), HumanGating.NONE);
        var graph = factory.of(List.of(cellNode), List.of());
        var ctx = new ProvisionContext("test", graph);

        var result = provisioner.provision(cellNode, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(world.isRevealed(5, 5)).isTrue();
    }

    @Test
    void provisionUnit_placesWithStrength() {
        var cellId = NodeId.of("cell-3-3");
        var unitId = NodeId.of("unit-1");
        var cellNode = new DesiredNode(cellId, SpatialNodeTypes.CELL,
            new CellSpec(3, 3, 0, TerrainType.OPEN), HumanGating.NONE);
        var unitNode = new DesiredNode(unitId, SpatialNodeTypes.UNIT,
            new UnitSpec(cellId, 10), HumanGating.NONE);
        var graph = factory.of(List.of(cellNode, unitNode),
            List.of(new Dependency(unitId, cellId)));
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(cellNode, ctx);
        var result = provisioner.provision(unitNode, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
        assertThat(world.isUnitPlaced(unitId)).isTrue();
        assertThat(world.unitStrength(unitId)).isEqualTo(10);
    }

    @Test
    void provisionScout_placesAndRevealsFog() {
        var cellId = NodeId.of("cell-5-5");
        var scoutId = NodeId.of("scout-1");
        var cellNode = new DesiredNode(cellId, SpatialNodeTypes.CELL,
            new CellSpec(5, 5, 0, TerrainType.OPEN), HumanGating.NONE);
        var scoutNode = new DesiredNode(scoutId, SpatialNodeTypes.SCOUT,
            new ScoutSpec(cellId, 2), HumanGating.NONE);
        var graph = factory.of(List.of(cellNode, scoutNode),
            List.of(new Dependency(scoutId, cellId)));
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(cellNode, ctx);
        provisioner.provision(scoutNode, ctx);

        assertThat(world.isRevealed(5, 7)).isTrue(); // vision range 2
    }

    @Test
    void provisionZone_recordsAllocation() {
        var cellId = NodeId.of("cell-5-5");
        var zoneId = NodeId.of("zone-north");
        var allocation = Map.of(cellId, 1.0);
        var cellNode = new DesiredNode(cellId, SpatialNodeTypes.CELL,
            new CellSpec(5, 5, 0, TerrainType.OPEN), HumanGating.NONE);
        var zoneNode = new DesiredNode(zoneId, SpatialNodeTypes.ZONE,
            new ZoneSpec("north", allocation, 100), HumanGating.NONE);
        var graph = factory.of(List.of(cellNode, zoneNode),
            List.of(new Dependency(zoneId, cellId)));
        var ctx = new ProvisionContext("test", graph);

        provisioner.provision(cellNode, ctx);
        var result = provisioner.provision(zoneNode, ctx);

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void deprovisionUnit_removesFromWorld() {
        var cellId = NodeId.of("cell-3-3");
        var unitId = NodeId.of("unit-1");
        var cellNode = new DesiredNode(cellId, SpatialNodeTypes.CELL,
            new CellSpec(3, 3, 0, TerrainType.OPEN), HumanGating.NONE);
        var unitNode = new DesiredNode(unitId, SpatialNodeTypes.UNIT,
            new UnitSpec(cellId, 10), HumanGating.NONE);
        var graph = factory.of(List.of(cellNode, unitNode),
            List.of(new Dependency(unitId, cellId)));
        var ctx = new ProvisionContext("test", graph);
        var dctx = new DeprovisionContext("test", graph);

        provisioner.provision(cellNode, ctx);
        provisioner.provision(unitNode, ctx);
        provisioner.deprovision(unitNode, dctx);

        assertThat(world.isUnitPlaced(unitId)).isFalse();
    }
}
