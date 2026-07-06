package io.casehub.desiredstate.example.spatial.distribution;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import java.util.*;

public class DistributionGoalCompiler implements GoalCompiler<DistributionBlueprint> {

    @Override
    public CompilationResult compile(DistributionBlueprint goals, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        var zoneId = NodeId.of("zone-" + goals.zoneName());
        var allocation = new HashMap<NodeId, Double>();

        for (var cellDef : goals.frontierCells()) {
            var cellId = NodeId.of("cell-%d-%d".formatted(cellDef.row(), cellDef.col()));
            nodes.add(new DesiredNode(cellId, SpatialNodeTypes.CELL,
                new CellSpec(cellDef.row(), cellDef.col(), 0, TerrainType.OPEN), false));
            allocation.put(cellId, cellDef.ratio());
            deps.add(new Dependency(zoneId, cellId));
        }

        var zoneSpec = new ZoneSpec(goals.zoneName(), allocation, goals.totalForce());
        nodes.add(new DesiredNode(zoneId, SpatialNodeTypes.ZONE, zoneSpec, false));

        for (var cellDef : goals.frontierCells()) {
            var cellId = NodeId.of("cell-%d-%d".formatted(cellDef.row(), cellDef.col()));
            var unitId = NodeId.of("unit-" + cellId.value());
            var strength = zoneSpec.strengthFor(cellId);
            nodes.add(new DesiredNode(unitId, SpatialNodeTypes.UNIT,
                new UnitSpec(cellId, strength), false));
            deps.add(new Dependency(unitId, cellId));
            deps.add(new Dependency(unitId, zoneId));
        }

        return CompilationResult.single(factory.of(nodes, deps));
    }
}
