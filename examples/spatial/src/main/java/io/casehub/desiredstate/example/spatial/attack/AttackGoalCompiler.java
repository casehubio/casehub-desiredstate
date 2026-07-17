package io.casehub.desiredstate.example.spatial.attack;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.example.spatial.specs.*;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;
import java.util.*;

public class AttackGoalCompiler implements GoalCompiler<AttackBlueprint> {

    @Override
    public CompilationResult compile(AttackBlueprint goals, DesiredStateGraphFactory factory) {
        var nodes = new ArrayList<DesiredNode>();
        var deps = new ArrayList<Dependency>();

        // Origin cell
        var originCellId = NodeId.of("cell-%d-%d".formatted(goals.originRow(), goals.originCol()));
        nodes.add(new DesiredNode(originCellId, SpatialNodeTypes.CELL,
            new CellSpec(goals.originRow(), goals.originCol(), 0, TerrainType.OPEN), HumanGating.NONE));

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

        // Waypoints — dependency chain through cells
        NodeId previousWaypointCellId = null;
        for (var wp : goals.waypoints()) {
            var wpCellId = NodeId.of("cell-%d-%d".formatted(wp.row(), wp.col()));
            if (nodes.stream().noneMatch(n -> n.id().equals(wpCellId))) {
                nodes.add(new DesiredNode(wpCellId, SpatialNodeTypes.CELL,
                    new CellSpec(wp.row(), wp.col(), 0, TerrainType.OPEN), HumanGating.NONE));
            }

            // Unit on waypoint cell
            var unitId = NodeId.of("unit-waypoint-%d-%d".formatted(wp.row(), wp.col()));
            nodes.add(new DesiredNode(unitId, SpatialNodeTypes.UNIT,
                new UnitSpec(wpCellId, wp.strength()), HumanGating.NONE));
            deps.add(new Dependency(unitId, wpCellId));

            // Dependency chain: current waypoint cell depends on previous waypoint cell
            if (previousWaypointCellId != null) {
                deps.add(new Dependency(wpCellId, previousWaypointCellId));
            }
            previousWaypointCellId = wpCellId;
        }

        return CompilationResult.single(factory.of(nodes, deps));
    }
}
