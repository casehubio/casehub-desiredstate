package io.casehub.desiredstate.example.spatial.defense;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import java.util.*;

public class DefenseGoalCompiler implements GoalCompiler<DefenseBlueprint> {

    @Override
    public CompilationResult compile(DefenseBlueprint goals, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        // Base cell
        var baseCellId = NodeId.of("cell-%d-%d".formatted(goals.baseRow(), goals.baseCol()));
        nodes.add(new DesiredNode(baseCellId, SpatialNodeTypes.CELL,
            new CellSpec(goals.baseRow(), goals.baseCol(), 0, TerrainType.OPEN), HumanGating.NONE));

        // Scout cells and scouts
        for (var pos : goals.scoutPositions()) {
            var scoutCellId = NodeId.of("cell-%d-%d".formatted(pos[0], pos[1]));
            if (nodes.stream().noneMatch(n -> n.id().equals(scoutCellId))) {
                nodes.add(new DesiredNode(scoutCellId, SpatialNodeTypes.CELL,
                    new CellSpec(pos[0], pos[1], 0, TerrainType.OPEN), HumanGating.NONE));
            }
            var scoutId = NodeId.of("scout-%d-%d".formatted(pos[0], pos[1]));
            nodes.add(new DesiredNode(scoutId, SpatialNodeTypes.SCOUT,
                new ScoutSpec(scoutCellId, 2), HumanGating.NONE));
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
                    nodes.add(new DesiredNode(cellId, SpatialNodeTypes.CELL,
                        new CellSpec(row, col, 0, TerrainType.OPEN), HumanGating.NONE));
                }
                deps.add(new Dependency(zoneId, cellId));
            }

            var zoneSpec = new ZoneSpec(zoneDef.name(), allocationByNodeId, zoneDef.totalForce());
            nodes.add(new DesiredNode(zoneId, SpatialNodeTypes.ZONE, zoneSpec, HumanGating.NONE));

            // Units per cell
            for (var entry : allocationByNodeId.entrySet()) {
                var cellId = entry.getKey();
                var unitId = NodeId.of("unit-" + cellId.value());
                var strength = zoneSpec.strengthFor(cellId);
                nodes.add(new DesiredNode(unitId, SpatialNodeTypes.UNIT,
                    new UnitSpec(cellId, strength), HumanGating.NONE));
                deps.add(new Dependency(unitId, cellId));
                deps.add(new Dependency(unitId, zoneId));
            }
        }

        return CompilationResult.single(factory.of(nodes, deps));
    }
}
