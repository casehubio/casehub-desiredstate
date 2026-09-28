package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.terrain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BattlefieldWorldTest {

    private TerrainGrid grid;
    private BattlefieldWorld world;

    @BeforeEach
    void setUp() {
        grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);
        world = new BattlefieldWorld(grid, 2);
    }

    @Test
    void initialState_noCellsRevealed() {
        assertThat(world.isRevealed(0, 0)).isFalse();
        assertThat(world.placedUnits()).isEmpty();
    }

    @Test
    void revealCell_marksCellRevealed() {
        world.revealCell(5, 5);
        assertThat(world.isRevealed(5, 5)).isTrue();
    }

    @Test
    void placeUnit_tracksUnitWithStrength() {
        var unitId = NodeId.of("unit-north-1");
        world.placeUnit(unitId, 5, 5, 10);

        assertThat(world.unitStrength(unitId)).isEqualTo(10);
        assertThat(world.isUnitPlaced(unitId)).isTrue();
    }

    @Test
    void removeUnit_removesTracking() {
        var unitId = NodeId.of("unit-north-1");
        world.placeUnit(unitId, 5, 5, 10);
        world.removeUnit(unitId);

        assertThat(world.isUnitPlaced(unitId)).isFalse();
    }

    @Test
    void placeScout_revealsWithinVisionRange() {
        world.placeScout(NodeId.of("scout-1"), 5, 5, 2);

        assertThat(world.isRevealed(5, 5)).isTrue();
        assertThat(world.isRevealed(5, 7)).isTrue();  // distance 2
        assertThat(world.isRevealed(5, 8)).isFalse(); // distance 3
    }

    @Test
    void removeUnit_removesFromWorld() {
        var unitId = NodeId.of("unit-1");
        world.placeUnit(unitId, 3, 3, 8);
        world.removeUnit(unitId);

        assertThat(world.isUnitPlaced(unitId)).isFalse();
    }

    @Test
    void updateUnitStrength_changesStrength() {
        var unitId = NodeId.of("unit-1");
        world.placeUnit(unitId, 3, 3, 8);
        world.updateUnitStrength(unitId, 5);

        assertThat(world.unitStrength(unitId)).isEqualTo(5);
    }
}
