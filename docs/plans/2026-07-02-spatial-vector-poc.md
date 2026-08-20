# Spatial/Vector POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build three standalone spatial scenarios (defense posture, attack waypoints, force distribution) on a shared 10x10 terrain grid to evaluate whether the graph model can handle spatial/continuous state — and document where it breaks.

**Architecture:** New `examples/spatial/` module alongside dungeon and pipeline. Pure Java tests — no Quarkus container. Each scenario implements GoalCompiler + blueprint, shares terrain model and world layer. Tests exercise the graph model via TransitionPlanner and direct provisioning. Scenario 3 includes a ZoneRebalanceFaultPolicy and strategic pivot tests that document failure modes.

**Tech Stack:** Java 21 records, JUnit 5, AssertJ, casehub-desiredstate-api + runtime + testing modules.

## Global Constraints

- Root package: `io.casehub.desiredstate.example.spatial`
- Maven artifact: `casehub-desiredstate-example-spatial`
- No Quarkus container — direct instantiation in tests
- No pathfinding algorithm — waypoints hand-placed
- No combat resolution — scripted event injection only
- NodeSpec records implement `NodeSpec` marker interface (has `default boolean requiresHuman() { return false; }`)
- Tests use `DefaultDesiredStateGraphFactory` and `TransitionPlanner` from runtime
- Assertions via AssertJ
- Spec: `docs/specs/2026-07-02-spatial-vector-poc-design.md` (workspace)

---

### Task 1: Module Scaffold + Terrain Model

**Files:**
- Create: `examples/spatial/pom.xml`
- Modify: `pom.xml` (add `<module>examples/spatial</module>`)
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/terrain/TerrainType.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/terrain/TerrainCell.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/terrain/TerrainGrid.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/terrain/FogOfWar.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/terrain/TerrainGridTest.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/terrain/FogOfWarTest.java`

**Interfaces:**
- Consumes: nothing (foundation task)
- Produces: `TerrainGrid`, `TerrainCell`, `TerrainType`, `FogOfWar` — used by all subsequent tasks

- [ ] **Step 1: Create pom.xml for the spatial module**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.casehub</groupId>
        <artifactId>casehub-desiredstate-parent</artifactId>
        <version>0.2-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>casehub-desiredstate-example-spatial</artifactId>

    <name>CaseHub Desired State :: Example :: Spatial</name>
    <description>POC — spatial/vector state representation stress test.
        Three scenarios evaluating the graph model with fog of war, zones, force distribution.</description>

    <dependencies>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.casehub</groupId>
            <artifactId>casehub-desiredstate-testing</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Add the module to parent pom.xml**

Add `<module>examples/spatial</module>` to the `<modules>` section after `examples/pipeline`.

- [ ] **Step 3: Write TerrainGridTest — failing tests for grid construction and adjacency**

```java
package io.casehub.desiredstate.example.spatial.terrain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TerrainGridTest {

    @Test
    void constructGrid_10x10_allCellsAccessible() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        assertThat(grid.rows()).isEqualTo(10);
        assertThat(grid.cols()).isEqualTo(10);
        assertThat(grid.cellAt(0, 0)).isNotNull();
        assertThat(grid.cellAt(9, 9)).isNotNull();
    }

    @Test
    void constructGrid_withHeightVariation() {
        var grid = TerrainGrid.create(3, 3, (r, c) -> r, (r, c) -> TerrainType.OPEN);

        assertThat(grid.cellAt(0, 0).height()).isEqualTo(0);
        assertThat(grid.cellAt(1, 0).height()).isEqualTo(1);
        assertThat(grid.cellAt(2, 0).height()).isEqualTo(2);
    }

    @Test
    void cellAt_outOfBounds_throws() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        assertThatThrownBy(() -> grid.cellAt(-1, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> grid.cellAt(10, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void adjacentCells_centerCell_returnsFourNeighbors() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        var adj = grid.adjacentCells(5, 5);
        assertThat(adj).hasSize(4);
        assertThat(adj).extracting(TerrainCell::row, TerrainCell::col)
            .containsExactlyInAnyOrder(
                tuple(4, 5), tuple(6, 5), tuple(5, 4), tuple(5, 6));
    }

    @Test
    void adjacentCells_cornerCell_returnsTwoNeighbors() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        var adj = grid.adjacentCells(0, 0);
        assertThat(adj).hasSize(2);
        assertThat(adj).extracting(TerrainCell::row, TerrainCell::col)
            .containsExactlyInAnyOrder(tuple(1, 0), tuple(0, 1));
    }

    @Test
    void adjacentCells_edgeCell_returnsThreeNeighbors() {
        var grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);

        var adj = grid.adjacentCells(0, 5);
        assertThat(adj).hasSize(3);
    }

    @Test
    void terrainTypes_mixedGrid() {
        var grid = TerrainGrid.create(3, 3,
            (r, c) -> (r == 1 && c == 1) ? 2 : 0,
            (r, c) -> (r == 1 && c == 1) ? TerrainType.CLIFF : TerrainType.OPEN);

        assertThat(grid.cellAt(1, 1).terrainType()).isEqualTo(TerrainType.CLIFF);
        assertThat(grid.cellAt(0, 0).terrainType()).isEqualTo(TerrainType.OPEN);
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=TerrainGridTest`
Expected: FAIL — classes do not exist yet.

- [ ] **Step 5: Implement TerrainType**

```java
package io.casehub.desiredstate.example.spatial.terrain;

public enum TerrainType {
    OPEN, CLIFF, RAMP
}
```

- [ ] **Step 6: Implement TerrainCell**

```java
package io.casehub.desiredstate.example.spatial.terrain;

import java.util.Objects;

public record TerrainCell(int row, int col, int height, TerrainType terrainType) {
    public TerrainCell {
        if (height < 0 || height > 2)
            throw new IllegalArgumentException("Height must be 0, 1, or 2, got: " + height);
        Objects.requireNonNull(terrainType, "terrainType must not be null");
    }
}
```

- [ ] **Step 7: Implement TerrainGrid**

```java
package io.casehub.desiredstate.example.spatial.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class TerrainGrid {
    private final TerrainCell[][] cells;
    private final int rows;
    private final int cols;

    private TerrainGrid(TerrainCell[][] cells, int rows, int cols) {
        this.cells = cells;
        this.rows = rows;
        this.cols = cols;
    }

    public static TerrainGrid create(int rows, int cols,
                                     BiFunction<Integer, Integer, Integer> heightFn,
                                     BiFunction<Integer, Integer, TerrainType> typeFn) {
        var cells = new TerrainCell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                cells[r][c] = new TerrainCell(r, c, heightFn.apply(r, c), typeFn.apply(r, c));
            }
        }
        return new TerrainGrid(cells, rows, cols);
    }

    public TerrainCell cellAt(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols)
            throw new IndexOutOfBoundsException(
                "(%d,%d) out of bounds for %dx%d grid".formatted(row, col, rows, cols));
        return cells[row][col];
    }

    public List<TerrainCell> adjacentCells(int row, int col) {
        var result = new ArrayList<TerrainCell>(4);
        if (row > 0) result.add(cells[row - 1][col]);
        if (row < rows - 1) result.add(cells[row + 1][col]);
        if (col > 0) result.add(cells[row][col - 1]);
        if (col < cols - 1) result.add(cells[row][col + 1]);
        return List.copyOf(result);
    }

    public int rows() { return rows; }
    public int cols() { return cols; }
}
```

- [ ] **Step 8: Run TerrainGridTest to verify it passes**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=TerrainGridTest`
Expected: All 7 tests PASS.

- [ ] **Step 9: Write FogOfWarTest — failing tests for vision and reveal**

```java
package io.casehub.desiredstate.example.spatial.terrain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FogOfWarTest {

    private TerrainGrid grid;

    @BeforeEach
    void setUp() {
        grid = TerrainGrid.create(10, 10, (r, c) -> 0, (r, c) -> TerrainType.OPEN);
    }

    @Test
    void initialState_nothingRevealed() {
        var fog = new FogOfWar(grid, 2);

        assertThat(fog.isRevealed(0, 0)).isFalse();
        assertThat(fog.isRevealed(5, 5)).isFalse();
        assertThat(fog.revealedCells()).isEmpty();
    }

    @Test
    void reveal_centerCell_visionRange2_revealsCorrectCells() {
        var fog = new FogOfWar(grid, 2);

        var newlyRevealed = fog.reveal(5, 5);

        // Manhattan distance <= 2 from (5,5): 13 cells
        // (5,5), (4,5),(6,5),(5,4),(5,6), (3,5),(7,5),(5,3),(5,7),
        // (4,4),(4,6),(6,4),(6,6)
        assertThat(newlyRevealed).hasSize(13);
        assertThat(fog.isRevealed(5, 5)).isTrue();
        assertThat(fog.isRevealed(5, 7)).isTrue();  // distance 2
        assertThat(fog.isRevealed(5, 8)).isFalse(); // distance 3
    }

    @Test
    void reveal_cornerCell_visionRange2_clipsToGridBounds() {
        var fog = new FogOfWar(grid, 2);

        var newlyRevealed = fog.reveal(0, 0);

        // (0,0), (0,1), (0,2), (1,0), (1,1), (2,0) = 6 cells
        assertThat(newlyRevealed).hasSize(6);
        assertThat(fog.isRevealed(0, 0)).isTrue();
        assertThat(fog.isRevealed(2, 0)).isTrue();
        assertThat(fog.isRevealed(3, 0)).isFalse();
    }

    @Test
    void reveal_overlappingVision_returnsOnlyNewCells() {
        var fog = new FogOfWar(grid, 2);

        var first = fog.reveal(5, 5);
        var second = fog.reveal(5, 6);

        // Second reveal should only return cells not already revealed
        assertThat(second).allSatisfy(cell ->
            assertThat(first).doesNotContain(cell));
    }

    @Test
    void reveal_visionRange1_revealsImmediateNeighborsOnly() {
        var fog = new FogOfWar(grid, 1);

        var revealed = fog.reveal(5, 5);

        // (5,5), (4,5), (6,5), (5,4), (5,6) = 5 cells
        assertThat(revealed).hasSize(5);
    }

    @Test
    void revealedCells_accumulatesAcrossMultipleReveals() {
        var fog = new FogOfWar(grid, 1);

        fog.reveal(0, 0);
        fog.reveal(9, 9);

        var all = fog.revealedCells();
        assertThat(all).contains(grid.cellAt(0, 0), grid.cellAt(9, 9));
    }
}
```

- [ ] **Step 10: Run FogOfWarTest to verify it fails**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=FogOfWarTest`
Expected: FAIL — FogOfWar class does not exist.

- [ ] **Step 11: Implement FogOfWar**

```java
package io.casehub.desiredstate.example.spatial.terrain;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class FogOfWar {
    private final TerrainGrid grid;
    private final int visionRange;
    private final Set<Long> revealed = new HashSet<>();

    public FogOfWar(TerrainGrid grid, int visionRange) {
        this.grid = Objects.requireNonNull(grid);
        if (visionRange < 1)
            throw new IllegalArgumentException("visionRange must be >= 1, got: " + visionRange);
        this.visionRange = visionRange;
    }

    public Set<TerrainCell> reveal(int row, int col) {
        var newlyRevealed = new HashSet<TerrainCell>();
        for (int r = row - visionRange; r <= row + visionRange; r++) {
            for (int c = col - visionRange; c <= col + visionRange; c++) {
                if (r < 0 || r >= grid.rows() || c < 0 || c >= grid.cols()) continue;
                int distance = Math.abs(r - row) + Math.abs(c - col);
                if (distance > visionRange) continue;
                long key = encodeKey(r, c);
                if (revealed.add(key)) {
                    newlyRevealed.add(grid.cellAt(r, c));
                }
            }
        }
        return Set.copyOf(newlyRevealed);
    }

    public boolean isRevealed(int row, int col) {
        return revealed.contains(encodeKey(row, col));
    }

    public Set<TerrainCell> revealedCells() {
        return revealed.stream()
            .map(key -> grid.cellAt(decodeRow(key), decodeCol(key)))
            .collect(Collectors.toUnmodifiableSet());
    }

    public int visionRange() { return visionRange; }

    private long encodeKey(int row, int col) {
        return (long) row * grid.cols() + col;
    }

    private int decodeRow(long key) { return (int) (key / grid.cols()); }
    private int decodeCol(long key) { return (int) (key % grid.cols()); }
}
```

- [ ] **Step 12: Run FogOfWarTest to verify it passes**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=FogOfWarTest`
Expected: All 6 tests PASS.

- [ ] **Step 13: Run full module tests**

Run: `mvn --batch-mode test -pl examples/spatial`
Expected: All 13 tests PASS.

- [ ] **Step 14: Commit**

```bash
git add examples/spatial/pom.xml pom.xml \
  examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/terrain/ \
  examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/terrain/
git commit -m "feat(#57): spatial module scaffold + terrain model — grid, cells, fog of war"
```

---

### Task 2: Domain Types + World Layer + GridRenderer

**Files:**
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/specs/SpatialNodeTypes.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/specs/CellSpec.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/specs/UnitSpec.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/specs/ScoutSpec.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/specs/ZoneSpec.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/world/BattlefieldWorld.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/world/BattlefieldActualStateAdapter.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/world/BattlefieldProvisioner.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/render/GridRenderer.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/world/BattlefieldWorldTest.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/world/BattlefieldProvisionerTest.java`

**Interfaces:**
- Consumes: `TerrainGrid`, `TerrainCell`, `TerrainType`, `FogOfWar` from Task 1
- Produces: `SpatialNodeTypes` (constants), `CellSpec`, `UnitSpec`, `ScoutSpec`, `ZoneSpec` (NodeSpec records), `BattlefieldWorld` (simulation state), `BattlefieldActualStateAdapter` (implements `ActualStateAdapter`), `BattlefieldProvisioner` (implements `NodeProvisioner`), `GridRenderer` (ANSI output)

- [ ] **Step 1: Write BattlefieldWorldTest — failing tests for world operations**

```java
package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
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
        world.placeScout(NodeId.of("scout-1"), 5, 5);

        assertThat(world.isRevealed(5, 5)).isTrue();
        assertThat(world.isRevealed(5, 7)).isTrue();  // distance 2
        assertThat(world.isRevealed(5, 8)).isFalse(); // distance 3
    }

    @Test
    void destroyUnit_removesFromWorld() {
        var unitId = NodeId.of("unit-1");
        world.placeUnit(unitId, 3, 3, 8);
        world.destroyUnit(unitId);

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
```

- [ ] **Step 2: Write BattlefieldProvisionerTest — failing tests for provision/deprovision**

```java
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
        var cellNode = new DesiredNode(NodeId.of("cell-5-5"), new CellSpec(5, 5, 0, TerrainType.OPEN), false);
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
        var cellNode = new DesiredNode(cellId, new CellSpec(3, 3, 0, TerrainType.OPEN), false);
        var unitNode = new DesiredNode(unitId, new UnitSpec(cellId, 10), false);
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
        var cellNode = new DesiredNode(cellId, new CellSpec(5, 5, 0, TerrainType.OPEN), false);
        var scoutNode = new DesiredNode(scoutId, new ScoutSpec(cellId, 2), false);
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
        var cellNode = new DesiredNode(cellId, new CellSpec(5, 5, 0, TerrainType.OPEN), false);
        var zoneNode = new DesiredNode(zoneId, new ZoneSpec("north", allocation, 100), false);
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
        var cellNode = new DesiredNode(cellId, new CellSpec(3, 3, 0, TerrainType.OPEN), false);
        var unitNode = new DesiredNode(unitId, new UnitSpec(cellId, 10), false);
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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest="BattlefieldWorldTest,BattlefieldProvisionerTest"`
Expected: FAIL — classes do not exist.

- [ ] **Step 4: Implement SpatialNodeTypes**

```java
package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeType;

public final class SpatialNodeTypes {
    public static final NodeType CELL = new NodeType("spatial:cell");
    public static final NodeType UNIT = new NodeType("spatial:unit");
    public static final NodeType SCOUT = new NodeType("spatial:scout");
    public static final NodeType ZONE = new NodeType("spatial:zone");

    private SpatialNodeTypes() {}
}
```

- [ ] **Step 5: Implement NodeSpec records**

CellSpec:
```java
package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;

public record CellSpec(int row, int col, int height, TerrainType terrainType) implements NodeSpec {}
```

UnitSpec:
```java
package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record UnitSpec(NodeId cellId, int strength) implements NodeSpec {}
```

ScoutSpec:
```java
package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;

public record ScoutSpec(NodeId cellId, int visionRange) implements NodeSpec {}
```

ZoneSpec:
```java
package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import java.util.Map;
import java.util.Objects;

public record ZoneSpec(String zoneName, Map<NodeId, Double> allocation, int totalForce)
        implements NodeSpec {
    public ZoneSpec {
        Objects.requireNonNull(zoneName);
        allocation = Map.copyOf(allocation);
        if (totalForce < 0)
            throw new IllegalArgumentException("totalForce must be >= 0");
    }

    public int strengthFor(NodeId cellId) {
        return (int) Math.round(totalForce * allocation.getOrDefault(cellId, 0.0));
    }
}
```

- [ ] **Step 6: Implement BattlefieldWorld**

```java
package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.spatial.terrain.FogOfWar;
import io.casehub.desiredstate.example.spatial.terrain.TerrainGrid;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class BattlefieldWorld {
    private final TerrainGrid grid;
    private final FogOfWar fog;
    private final Map<NodeId, UnitPlacement> units = new HashMap<>();
    private final Map<NodeId, ScoutPlacement> scouts = new HashMap<>();
    private final Map<NodeId, Boolean> zones = new HashMap<>();

    public BattlefieldWorld(TerrainGrid grid, int visionRange) {
        this.grid = Objects.requireNonNull(grid);
        this.fog = new FogOfWar(grid, visionRange);
    }

    public TerrainGrid grid() { return grid; }
    public FogOfWar fog() { return fog; }

    public void revealCell(int row, int col) { fog.reveal(row, col); }
    public boolean isRevealed(int row, int col) { return fog.isRevealed(row, col); }

    public void placeUnit(NodeId id, int row, int col, int strength) {
        units.put(id, new UnitPlacement(row, col, strength));
    }

    public void removeUnit(NodeId id) { units.remove(id); }
    public void destroyUnit(NodeId id) { units.remove(id); }

    public boolean isUnitPlaced(NodeId id) { return units.containsKey(id); }

    public int unitStrength(NodeId id) {
        var placement = units.get(id);
        if (placement == null) throw new IllegalArgumentException("Unit not placed: " + id);
        return placement.strength();
    }

    public void updateUnitStrength(NodeId id, int newStrength) {
        var placement = units.get(id);
        if (placement == null) throw new IllegalArgumentException("Unit not placed: " + id);
        units.put(id, new UnitPlacement(placement.row(), placement.col(), newStrength));
    }

    public void placeScout(NodeId id, int row, int col) {
        scouts.put(id, new ScoutPlacement(row, col));
        fog.reveal(row, col);
    }

    public void removeScout(NodeId id) { scouts.remove(id); }
    public boolean isScoutPlaced(NodeId id) { return scouts.containsKey(id); }

    public void activateZone(NodeId id) { zones.put(id, true); }
    public void deactivateZone(NodeId id) { zones.remove(id); }
    public boolean isZoneActive(NodeId id) { return zones.containsKey(id); }

    public Map<NodeId, UnitPlacement> placedUnits() { return Map.copyOf(units); }

    public record UnitPlacement(int row, int col, int strength) {}
    public record ScoutPlacement(int row, int col) {}
}
```

- [ ] **Step 7: Implement BattlefieldActualStateAdapter**

```java
package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import java.util.HashMap;
import java.util.Map;

public class BattlefieldActualStateAdapter implements ActualStateAdapter {
    private final BattlefieldWorld world;

    public BattlefieldActualStateAdapter(BattlefieldWorld world) {
        this.world = world;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        var statuses = new HashMap<NodeId, NodeStatus>();
        for (var entry : desired.nodes().entrySet()) {
            var nodeId = entry.getKey();
            var node = entry.getValue();
            statuses.put(nodeId, statusOf(node));
        }
        return new ActualState(statuses);
    }

    private NodeStatus statusOf(DesiredNode node) {
        var spec = node.spec();
        if (spec instanceof CellSpec cellSpec) {
            return world.isRevealed(cellSpec.row(), cellSpec.col())
                ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }
        if (spec instanceof UnitSpec unitSpec) {
            if (!world.isUnitPlaced(node.id())) return NodeStatus.ABSENT;
            return world.unitStrength(node.id()) == unitSpec.strength()
                ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        }
        if (spec instanceof ScoutSpec) {
            return world.isScoutPlaced(node.id())
                ? NodeStatus.PRESENT : NodeStatus.ABSENT;
        }
        if (spec instanceof ZoneSpec zoneSpec) {
            if (!world.isZoneActive(node.id())) return NodeStatus.ABSENT;
            return isZoneConsistent(node.id(), zoneSpec)
                ? NodeStatus.PRESENT : NodeStatus.DRIFTED;
        }
        return NodeStatus.UNKNOWN;
    }

    private boolean isZoneConsistent(NodeId zoneId, ZoneSpec zoneSpec) {
        for (var entry : zoneSpec.allocation().entrySet()) {
            var cellId = entry.getKey();
            var expectedStrength = zoneSpec.strengthFor(cellId);
            var unitId = unitIdForCell(cellId);
            if (unitId == null) return false;
            if (!world.isUnitPlaced(unitId)) return false;
            if (world.unitStrength(unitId) != expectedStrength) return false;
        }
        return true;
    }

    private NodeId unitIdForCell(NodeId cellId) {
        return NodeId.of("unit-" + cellId.value());
    }
}
```

- [ ] **Step 8: Implement BattlefieldProvisioner**

```java
package io.casehub.desiredstate.example.spatial.world;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import java.time.Duration;
import java.util.Set;

public class BattlefieldProvisioner implements NodeProvisioner {
    private final BattlefieldWorld world;

    public BattlefieldProvisioner(BattlefieldWorld world) {
        this.world = world;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return Set.of(SpatialNodeTypes.CELL, SpatialNodeTypes.UNIT,
                      SpatialNodeTypes.SCOUT, SpatialNodeTypes.ZONE);
    }

    @Override
    public Duration resyncInterval() { return Duration.ofMinutes(1); }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        var spec = node.spec();
        if (spec instanceof CellSpec cellSpec) {
            world.revealCell(cellSpec.row(), cellSpec.col());
            return new ProvisionResult.Success();
        }
        if (spec instanceof UnitSpec unitSpec) {
            var cellSpec = (CellSpec) context.graph().nodes()
                .get(unitSpec.cellId()).spec();
            world.placeUnit(node.id(), cellSpec.row(), cellSpec.col(),
                           unitSpec.strength());
            return new ProvisionResult.Success();
        }
        if (spec instanceof ScoutSpec scoutSpec) {
            var cellSpec = (CellSpec) context.graph().nodes()
                .get(scoutSpec.cellId()).spec();
            world.placeScout(node.id(), cellSpec.row(), cellSpec.col());
            return new ProvisionResult.Success();
        }
        if (spec instanceof ZoneSpec) {
            world.activateZone(node.id());
            return new ProvisionResult.Success();
        }
        return new ProvisionResult.Failed("Unknown spec type: " + spec.getClass());
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        var spec = node.spec();
        if (spec instanceof CellSpec) {
            return new DeprovisionResult.Success();
        }
        if (spec instanceof UnitSpec) {
            world.removeUnit(node.id());
            return new DeprovisionResult.Success();
        }
        if (spec instanceof ScoutSpec) {
            world.removeScout(node.id());
            return new DeprovisionResult.Success();
        }
        if (spec instanceof ZoneSpec) {
            world.deactivateZone(node.id());
            return new DeprovisionResult.Success();
        }
        return new DeprovisionResult.Failed("Unknown spec type: " + spec.getClass());
    }
}
```

- [ ] **Step 9: Implement GridRenderer**

```java
package io.casehub.desiredstate.example.spatial.render;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import io.casehub.desiredstate.example.spatial.world.BattlefieldWorld;

public class GridRenderer {
    private final BattlefieldWorld world;
    private final long stepDelayMs;
    private boolean firstFrame = true;

    public GridRenderer(BattlefieldWorld world, long stepDelayMs) {
        this.world = world;
        this.stepDelayMs = stepDelayMs;
    }

    public GridRenderer(BattlefieldWorld world) {
        this(world, 500);
    }

    public String render(String annotation) {
        var grid = world.grid();
        var sb = new StringBuilder();
        sb.append(annotation).append('\n');
        sb.append("   ");
        for (int c = 0; c < grid.cols(); c++) sb.append("%2d ".formatted(c));
        sb.append('\n');

        var units = world.placedUnits();

        for (int r = 0; r < grid.rows(); r++) {
            sb.append("%2d ".formatted(r));
            for (int c = 0; c < grid.cols(); c++) {
                var cell = grid.cellAt(r, c);
                if (!world.isRevealed(r, c)) {
                    sb.append("░░ ");
                } else {
                    var unitOnCell = findUnitAt(r, c);
                    if (unitOnCell != null) {
                        var strength = world.unitStrength(unitOnCell);
                        sb.append("U%d ".formatted(Math.min(strength, 9)));
                    } else if (cell.terrainType() == TerrainType.CLIFF) {
                        sb.append("## ");
                    } else if (cell.height() > 0) {
                        sb.append("▲%d ".formatted(cell.height()));
                    } else {
                        sb.append(" . ");
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void printFrame(String annotation) {
        if (!firstFrame) {
            System.out.print("\033[H\033[2J");
        }
        firstFrame = false;
        System.out.print(render(annotation));
        System.out.flush();
        if (stepDelayMs > 0) {
            try { Thread.sleep(stepDelayMs); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private NodeId findUnitAt(int row, int col) {
        for (var entry : world.placedUnits().entrySet()) {
            var placement = entry.getValue();
            if (placement.row() == row && placement.col() == col) {
                return entry.getKey();
            }
        }
        return null;
    }
}
```

- [ ] **Step 10: Run all tests to verify they pass**

Run: `mvn --batch-mode test -pl examples/spatial`
Expected: All tests PASS (TerrainGrid 7 + FogOfWar 6 + BattlefieldWorld 7 + BattlefieldProvisioner 6 = 26).

- [ ] **Step 11: Commit**

```bash
git add examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/specs/ \
  examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/world/ \
  examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/render/ \
  examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/world/
git commit -m "feat(#57): domain types, world layer, provisioner, grid renderer"
```

---

### Task 3: Scenario 1 — Defense Posture (Layers 1–2)

**Files:**
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/defense/DefenseBlueprint.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/defense/DefenseGoalCompiler.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/defense/DefensePostureTest.java`

**Interfaces:**
- Consumes: `TerrainGrid`, `FogOfWar`, `BattlefieldWorld`, `BattlefieldActualStateAdapter`, `BattlefieldProvisioner`, `GridRenderer`, `SpatialNodeTypes`, all NodeSpec types, `DefaultDesiredStateGraphFactory`, `TransitionPlanner`
- Produces: `DefenseBlueprint`, `DefenseGoalCompiler` — standalone, not consumed by other tasks

- [ ] **Step 1: Write DefensePostureTest — all 5 test methods**

```java
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

        var graph = compiler.compile(blueprint, factory);

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
        var graph1 = compiler.compile(blueprint1, factory);
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
        var graph2 = compiler.compile(blueprint2, factory);
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
        var graph = compiler.compile(blueprint, factory);
        var actual1 = adapter.readActual(graph, "test");
        var plan1 = planner.plan(graph, actual1);
        for (var step : plan1.additions()) {
            provisioner.provision(step.node(), new ProvisionContext("test", graph));
        }

        // Inject: destroy unit
        world.destroyUnit(NodeId.of("unit-cell-1-0"));

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
        var graph1 = compiler.compile(blueprint1, factory);
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
        var graph2 = compiler.compile(blueprint2, factory);
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
        var graph1 = compiler.compile(blueprint1, factory);
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
        var graph2 = compiler.compile(blueprint2, factory);
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=DefensePostureTest`
Expected: FAIL — DefenseBlueprint and DefenseGoalCompiler do not exist.

- [ ] **Step 3: Implement DefenseBlueprint**

```java
package io.casehub.desiredstate.example.spatial.defense;

import java.util.*;

public record DefenseBlueprint(
    int baseRow, int baseCol,
    List<int[]> scoutPositions,
    List<ZoneDefinition> zones
) {
    public record ZoneDefinition(String name, Map<String, Double> allocation, int totalForce) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int baseRow, baseCol;
        private final List<int[]> scouts = new ArrayList<>();
        private final List<ZoneDefinition> zones = new ArrayList<>();

        public Builder basePosition(int row, int col) {
            this.baseRow = row; this.baseCol = col; return this;
        }

        public Builder scout(int row, int col) {
            scouts.add(new int[]{row, col}); return this;
        }

        public Builder zone(String name, Map<String, Double> allocation, int totalForce) {
            zones.add(new ZoneDefinition(name, allocation, totalForce)); return this;
        }

        public DefenseBlueprint build() {
            return new DefenseBlueprint(baseRow, baseCol,
                List.copyOf(scouts), List.copyOf(zones));
        }
    }
}
```

- [ ] **Step 4: Implement DefenseGoalCompiler**

```java
package io.casehub.desiredstate.example.spatial.defense;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import java.util.*;

public class DefenseGoalCompiler implements GoalCompiler<DefenseBlueprint> {

    @Override
    public DesiredStateGraph compile(DefenseBlueprint goals, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        // Base cell
        var baseCellId = NodeId.of("cell-%d-%d".formatted(goals.baseRow(), goals.baseCol()));
        nodes.add(new DesiredNode(baseCellId, new CellSpec(goals.baseRow(), goals.baseCol(), 0, TerrainType.OPEN), false));

        // Scout cells and scouts
        for (var pos : goals.scoutPositions()) {
            var scoutCellId = NodeId.of("cell-%d-%d".formatted(pos[0], pos[1]));
            if (nodes.stream().noneMatch(n -> n.id().equals(scoutCellId))) {
                nodes.add(new DesiredNode(scoutCellId, new CellSpec(pos[0], pos[1], 0, TerrainType.OPEN), false));
            }
            var scoutId = NodeId.of("scout-%d-%d".formatted(pos[0], pos[1]));
            nodes.add(new DesiredNode(scoutId, new ScoutSpec(scoutCellId, 2), false));
            deps.add(new Dependency(scoutId, scoutCellId));
        }

        // Zones and units
        for (var zoneDef : goals.zones()) {
            var zoneId = NodeId.of("zone-" + zoneDef.name());
            var allocationByNodeId = new HashMap<NodeId, Double>();

            for (var entry : zoneDef.allocation().entrySet()) {
                var cellIdStr = entry.getKey();
                var cellId = NodeId.of(cellIdStr);
                var ratio = entry.getValue();
                allocationByNodeId.put(cellId, ratio);

                // Ensure cell node exists
                if (nodes.stream().noneMatch(n -> n.id().equals(cellId))) {
                    var parts = cellIdStr.replace("cell-", "").split("-");
                    var row = Integer.parseInt(parts[0]);
                    var col = Integer.parseInt(parts[1]);
                    nodes.add(new DesiredNode(cellId, new CellSpec(row, col, 0, TerrainType.OPEN), false));
                }
                deps.add(new Dependency(zoneId, cellId));
            }

            var zoneSpec = new ZoneSpec(zoneDef.name(), allocationByNodeId, zoneDef.totalForce());
            nodes.add(new DesiredNode(zoneId, zoneSpec, false));

            // Units per cell
            for (var entry : allocationByNodeId.entrySet()) {
                var cellId = entry.getKey();
                var unitId = NodeId.of("unit-" + cellId.value());
                var strength = zoneSpec.strengthFor(cellId);
                nodes.add(new DesiredNode(unitId, new UnitSpec(cellId, strength), false));
                deps.add(new Dependency(unitId, cellId));
                deps.add(new Dependency(unitId, zoneId));
            }
        }

        return factory.of(nodes, deps);
    }
}
```

- [ ] **Step 5: Run DefensePostureTest**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=DefensePostureTest`
Expected: All 5 tests PASS.

- [ ] **Step 6: Run full module tests**

Run: `mvn --batch-mode test -pl examples/spatial`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/defense/ \
  examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/defense/
git commit -m "feat(#57): scenario 1 — defense posture (layers 1-2)"
```

---

### Task 4: Scenario 2 — Attack with Waypoints (Layers 1–2)

**Files:**
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/attack/AttackBlueprint.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/attack/AttackGoalCompiler.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/attack/AttackWaypointsTest.java`

**Interfaces:**
- Consumes: all shared infrastructure from Tasks 1–2
- Produces: `AttackBlueprint`, `AttackGoalCompiler` — standalone

- [ ] **Step 1: Write AttackWaypointsTest — all 5 test methods**

```java
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

        var graph = compiler.compile(blueprint, factory);

        // Verify dependency chain: wp3 → wp2 → wp1
        var wp1 = NodeId.of("waypoint-1-1");
        var wp2 = NodeId.of("waypoint-2-2");
        var wp3 = NodeId.of("waypoint-3-3");
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
        var graph1 = compiler.compile(blueprint1, factory);
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
        var graph2 = compiler.compile(blueprint2, factory);
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
        var graph1 = compiler.compile(blueprint1, factory);
        executeAdditions(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1);

        // Losses at waypoint (2,2) → recompile with more force there
        var blueprint2 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(1, 1, 25)
            .waypoint(2, 2, 50) // reinforced
            .waypoint(3, 3, 25)
            .totalForce(100)
            .build();
        var graph2 = compiler.compile(blueprint2, factory);
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
        var graph1 = compiler.compile(blueprint, factory);
        executeAdditions(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1);

        // Incremental mutation: update waypoint (2,2) strength to 50
        var wp2Id = NodeId.of("waypoint-2-2");
        var unitId = NodeId.of("unit-waypoint-2-2");
        var graph2 = graph1
            .withMutation(new GraphMutation.UpdateNode(unitId, new UnitSpec(wp2Id, 50)));

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
        var graph1 = compiler.compile(blueprint1, factory);
        executeAdditions(planner.plan(graph1, adapter.readActual(graph1, "test")), graph1);

        // High ground at (7,8) revealed as occupied → need more force at (5,5)
        var blueprint2 = AttackBlueprint.builder()
            .origin(0, 0)
            .waypoint(2, 2, 20)
            .waypoint(3, 3, 20)
            .waypoint(5, 5, 60) // hardened
            .totalForce(100)
            .build();
        var graph2 = compiler.compile(blueprint2, factory);
        var actual2 = adapter.readActual(graph2, "test");
        var plan2 = planner.plan(graph2, actual2);

        executeRemovals(plan2, graph1);
        executeAdditions(plan2, graph2);
        assertThat(world.unitStrength(NodeId.of("unit-waypoint-5-5"))).isEqualTo(60);
        renderer.printFrame("After high ground discovery");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=AttackWaypointsTest`
Expected: FAIL — AttackBlueprint and AttackGoalCompiler do not exist.

- [ ] **Step 3: Implement AttackBlueprint**

```java
package io.casehub.desiredstate.example.spatial.attack;

import java.util.*;

public record AttackBlueprint(
    int originRow, int originCol,
    List<int[]> scoutPositions,
    List<WaypointDef> waypoints,
    int totalForce
) {
    public record WaypointDef(int row, int col, int strength) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int originRow, originCol;
        private final List<int[]> scouts = new ArrayList<>();
        private final List<WaypointDef> waypoints = new ArrayList<>();
        private int totalForce;

        public Builder origin(int row, int col) {
            this.originRow = row; this.originCol = col; return this;
        }

        public Builder scout(int row, int col) {
            scouts.add(new int[]{row, col}); return this;
        }

        public Builder waypoint(int row, int col, int strength) {
            waypoints.add(new WaypointDef(row, col, strength)); return this;
        }

        public Builder totalForce(int force) {
            this.totalForce = force; return this;
        }

        public AttackBlueprint build() {
            return new AttackBlueprint(originRow, originCol,
                List.copyOf(scouts), List.copyOf(waypoints), totalForce);
        }
    }
}
```

- [ ] **Step 4: Implement AttackGoalCompiler**

```java
package io.casehub.desiredstate.example.spatial.attack;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import java.util.*;

public class AttackGoalCompiler implements GoalCompiler<AttackBlueprint> {

    @Override
    public DesiredStateGraph compile(AttackBlueprint goals, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        // Origin cell
        var originCellId = NodeId.of("cell-%d-%d".formatted(goals.originRow(), goals.originCol()));
        nodes.add(new DesiredNode(originCellId, new CellSpec(goals.originRow(), goals.originCol(), 0, TerrainType.OPEN), false));

        // Scout cells and scouts
        for (var pos : goals.scoutPositions()) {
            var scoutCellId = NodeId.of("cell-%d-%d".formatted(pos[0], pos[1]));
            if (nodes.stream().noneMatch(n -> n.id().equals(scoutCellId))) {
                nodes.add(new DesiredNode(scoutCellId, new CellSpec(pos[0], pos[1], 0, TerrainType.OPEN), false));
            }
            var scoutId = NodeId.of("scout-%d-%d".formatted(pos[0], pos[1]));
            nodes.add(new DesiredNode(scoutId, new ScoutSpec(scoutCellId, 2), false));
            deps.add(new Dependency(scoutId, scoutCellId));
        }

        // Waypoints — dependency chain
        NodeId previousWaypointId = null;
        for (var wp : goals.waypoints()) {
            var wpCellId = NodeId.of("cell-%d-%d".formatted(wp.row(), wp.col()));
            if (nodes.stream().noneMatch(n -> n.id().equals(wpCellId))) {
                nodes.add(new DesiredNode(wpCellId, new CellSpec(wp.row(), wp.col(), 0, TerrainType.OPEN), false));
            }

            var wpId = NodeId.of("waypoint-%d-%d".formatted(wp.row(), wp.col()));
            nodes.add(new DesiredNode(wpId, new CellSpec(wp.row(), wp.col(), 0, TerrainType.OPEN), false));

            // Actually, waypoints are conceptual — use UNIT type with cell dependency
            // Re-think: waypoint IS a position with force. Model as UNIT on that cell.
            var unitId = NodeId.of("unit-waypoint-%d-%d".formatted(wp.row(), wp.col()));
            nodes.add(new DesiredNode(unitId, new UnitSpec(wpCellId, wp.strength()), false));
            deps.add(new Dependency(unitId, wpCellId));

            if (previousWaypointId != null) {
                deps.add(new Dependency(wpCellId, previousWaypointId));
            }
            previousWaypointId = wpCellId;
        }

        // Remove duplicate cell nodes (waypoints may share cells with scouts)
        var uniqueNodes = new ArrayList<DesiredNode>();
        var seenIds = new HashSet<NodeId>();
        for (var node : nodes) {
            if (seenIds.add(node.id())) {
                uniqueNodes.add(node);
            }
        }

        return factory.of(uniqueNodes, deps);
    }
}
```

- [ ] **Step 5: Run AttackWaypointsTest**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=AttackWaypointsTest`
Expected: All 5 tests PASS.

- [ ] **Step 6: Run full module tests**

Run: `mvn --batch-mode test -pl examples/spatial`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/attack/ \
  examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/attack/
git commit -m "feat(#57): scenario 2 — attack with waypoints (layers 1-2)"
```

---

### Task 5: Scenario 3 — Force Distribution (Layers 1–4)

**Files:**
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/DistributionBlueprint.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/DistributionGoalCompiler.java`
- Create: `examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/ZoneRebalanceFaultPolicy.java`
- Test: `examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/distribution/ForceDistributionTest.java`

**Interfaces:**
- Consumes: all shared infrastructure from Tasks 1–2, `FaultPolicy` SPI, `FaultEvent`, `FaultType`, `GraphMutation`
- Produces: `DistributionBlueprint`, `DistributionGoalCompiler`, `ZoneRebalanceFaultPolicy` — standalone

This is the capstone task. Layers 1–2 should pass. Layers 3–4 should document failure modes.

- [ ] **Step 1: Write ForceDistributionTest — all 7 test methods**

```java
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
        world.destroyUnit(NodeId.of("unit-cell-4-0"));

        // ActualStateAdapter reports zone as DRIFTED (member unit missing)
        var actual = adapter.readActual(graph, "test");
        assertThat(actual.statusOf(NodeId.of("zone-frontier"))).hasValue(NodeStatus.DRIFTED);

        // Fault event for the zone
        var faultEvent = new FaultEvent(
            NodeId.of("zone-frontier"), FaultType.NODE_DEGRADED,
            "Zone member unit missing or strength mismatch");

        // ZoneRebalanceFaultPolicy attempts redistribution
        var policy = new ZoneRebalanceFaultPolicy();
        var mutations = policy.onFault(faultEvent, graph);

        // FINDING: The policy receives (FaultEvent, DesiredStateGraph) but NOT ActualState.
        // It knows the zone is degraded but cannot determine WHICH unit was lost.
        // The FaultEvent carries the zone's NodeId — not the destroyed unit's NodeId.
        // The policy must return empty or make blind guesses.
        assertThat(mutations).as(
            "Fault policy cannot determine which unit was lost — " +
            "FaultPolicy SPI receives (FaultEvent, DesiredStateGraph) but not ActualState. " +
            "Finding #3: fault policy information gap.").isEmpty();

        // Meanwhile, the planner independently detects the missing unit
        var plan = planner.plan(graph, actual);
        assertThat(plan.additions()).anyMatch(step ->
            step.node().id().equals(NodeId.of("unit-cell-4-0")));

        // FINDING: The planner restores the unit with original strength (50).
        // Even if the policy COULD redistribute, it would conflict with the planner's
        // restoration — Finding #9: fault policy / planner conflict.
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
            world.destroyUnit(NodeId.of("unit-cell-4-0"));
            var actual = adapter.readActual(graph, "test");
            var faultEvent = new FaultEvent(
                NodeId.of("zone-frontier"), FaultType.NODE_DEGRADED,
                "Cycle " + cycle + ": zone member destroyed");
            allMutations.add(policy.onFault(faultEvent, graph));

            // Planner restores the unit
            var plan = planner.plan(graph, actual);
            executeAll(plan, graph, graph);
        }

        // FINDING: Each cycle is handled independently. No mechanism detects
        // the pattern "we keep losing units at this position."
        // The fault policy has no memory, no cycle count, no aggregate view.
        // Finding #4: no aggregate subgraph evaluation.
        // Finding #6: no correlated fault detection.
        assertThat(allMutations).as(
            "Three consecutive losses at the same position. " +
            "Each handled independently — no pattern detection, no escalation. " +
            "Findings #4 (no aggregate evaluation) and #6 (no correlated faults).")
            .allSatisfy(mutations -> assertThat(mutations).isEmpty());
    }

    @Test
    void strategicPivotRequiresExternalIntervention() {
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
        // No SPI can make this decision. It requires:
        // 1. Evaluating aggregate success of "north-approach" subgraph
        // 2. Knowing that "south-approach" is an alternative
        // 3. Making a cost/benefit decision to abandon north for south

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

        // But the DECISION to pivot has no home in the current model.
        // FINDING: The pivot requires external intervention — a human or
        // a higher-level system must decide to recompile with a different
        // blueprint. The runtime can execute the transition but cannot
        // initiate it. Finding #5: no strategic alternatives.
        assertThat(graph2.nodes().keySet()).as(
            "Graph transitioned from north to south approach. " +
            "The runtime executed the transition — but the DECISION to pivot " +
            "required external intervention (manual recompilation). " +
            "Finding #5: no strategic alternatives in current model.")
            .noneMatch(id -> id.value().contains("north"));
        renderer.printFrame("After strategic pivot to south");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=ForceDistributionTest`
Expected: FAIL — DistributionBlueprint, DistributionGoalCompiler, ZoneRebalanceFaultPolicy do not exist.

- [ ] **Step 3: Implement DistributionBlueprint**

```java
package io.casehub.desiredstate.example.spatial.distribution;

import java.util.*;

public record DistributionBlueprint(
    List<FrontierCellDef> frontierCells,
    int totalForce,
    String zoneName
) {
    public record FrontierCellDef(int row, int col, double ratio) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final List<FrontierCellDef> cells = new ArrayList<>();
        private int totalForce;
        private String zoneName = "frontier";

        public Builder frontierCell(int row, int col, double ratio) {
            cells.add(new FrontierCellDef(row, col, ratio)); return this;
        }

        public Builder totalForce(int force) {
            this.totalForce = force; return this;
        }

        public Builder zoneName(String name) {
            this.zoneName = name; return this;
        }

        public DistributionBlueprint build() {
            return new DistributionBlueprint(List.copyOf(cells), totalForce, zoneName);
        }
    }
}
```

- [ ] **Step 4: Implement DistributionGoalCompiler**

```java
package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import java.util.*;

public class DistributionGoalCompiler implements GoalCompiler<DistributionBlueprint> {

    @Override
    public DesiredStateGraph compile(DistributionBlueprint goals, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        var zoneId = NodeId.of("zone-" + goals.zoneName());
        var allocation = new HashMap<NodeId, Double>();

        for (var cellDef : goals.frontierCells()) {
            var cellId = NodeId.of("cell-%d-%d".formatted(cellDef.row(), cellDef.col()));
            nodes.add(new DesiredNode(cellId, new CellSpec(cellDef.row(), cellDef.col(), 0, TerrainType.OPEN), false));
            allocation.put(cellId, cellDef.ratio());
            deps.add(new Dependency(zoneId, cellId));
        }

        var zoneSpec = new ZoneSpec(goals.zoneName(), allocation, goals.totalForce());
        nodes.add(new DesiredNode(zoneId, zoneSpec, false));

        for (var cellDef : goals.frontierCells()) {
            var cellId = NodeId.of("cell-%d-%d".formatted(cellDef.row(), cellDef.col()));
            var unitId = NodeId.of("unit-" + cellId.value());
            var strength = zoneSpec.strengthFor(cellId);
            nodes.add(new DesiredNode(unitId, new UnitSpec(cellId, strength), false));
            deps.add(new Dependency(unitId, cellId));
            deps.add(new Dependency(unitId, zoneId));
        }

        return factory.of(nodes, deps);
    }
}
```

- [ ] **Step 5: Implement ZoneRebalanceFaultPolicy**

```java
package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.ZoneSpec;
import java.util.List;

public class ZoneRebalanceFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        var node = current.nodes().get(event.node());
        if (node == null || !(node.spec() instanceof ZoneSpec)) {
            return List.of();
        }

        // The policy knows the zone is degraded but CANNOT determine which
        // child unit was lost. The FaultPolicy SPI receives:
        //   (FaultEvent, DesiredStateGraph)
        // but NOT ActualState. The FaultEvent carries the zone's NodeId
        // and a generic detail string — not enough to diagnose the specific failure.
        //
        // To redistribute, the policy would need to:
        // 1. Know which unit(s) are ABSENT — requires ActualState
        // 2. Recompute ratios excluding lost cells — requires zone structure knowledge
        // 3. Emit UpdateNode mutations for zone + all remaining units — N+1 mutations
        //
        // Even if it could do all this, the TransitionPlanner independently detects
        // the missing unit as ABSENT and schedules re-provisioning with the original
        // spec. The policy's redistribution and the planner's restoration would conflict.
        //
        // Returning empty to document the information gap.
        return List.of();
    }
}
```

- [ ] **Step 6: Run ForceDistributionTest**

Run: `mvn --batch-mode test -pl examples/spatial -Dtest=ForceDistributionTest`
Expected: All 7 tests PASS.

- [ ] **Step 7: Run full module tests**

Run: `mvn --batch-mode test -pl examples/spatial`
Expected: All tests PASS.

- [ ] **Step 8: Run full project build**

Run: `mvn --batch-mode install`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add examples/spatial/src/main/java/io/casehub/desiredstate/example/spatial/distribution/ \
  examples/spatial/src/test/java/io/casehub/desiredstate/example/spatial/distribution/
git commit -m "feat(#57): scenario 3 — force distribution (layers 1-4) with failure mode documentation"
```
