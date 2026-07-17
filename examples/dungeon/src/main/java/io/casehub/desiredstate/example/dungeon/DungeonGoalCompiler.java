package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a {@link DungeonBlueprint} into a {@link DesiredStateGraph}.
 */
public class DungeonGoalCompiler implements GoalCompiler<DungeonBlueprint> {

    @Override
    public CompilationResult compile(DungeonBlueprint goals, DesiredStateGraphFactory factory) {
        List<DesiredNode> nodes = new ArrayList<>();
        List<Dependency> dependencies = new ArrayList<>();

        // Compile rooms
        for (DungeonBlueprint.RoomEntry room : goals.rooms()) {
            NodeId nodeId = NodeId.of(room.id());
            DungeonRoomSpec spec = new DungeonRoomSpec(room.id(), room.description(), room.size());
            nodes.add(new DesiredNode(nodeId, DungeonNodeTypes.ROOM, spec, HumanGating.NONE));
        }

        // Compile creatures
        for (DungeonBlueprint.CreatureEntry creature : goals.creatures()) {
            NodeId nodeId = NodeId.of(creature.id());
            CreatureSpec spec = new CreatureSpec(creature.species(), creature.level(), creature.humanGating());
            nodes.add(new DesiredNode(nodeId, DungeonNodeTypes.CREATURE, spec, HumanGating.NONE));

            // Add dependencies to rooms
            for (String roomDep : creature.roomDeps()) {
                dependencies.add(new Dependency(nodeId, NodeId.of(roomDep)));
            }
        }

        // Compile traps
        for (DungeonBlueprint.TrapEntry trap : goals.traps()) {
            NodeId nodeId = NodeId.of(trap.id());
            TrapSpec spec = new TrapSpec(trap.type(), trap.damage());
            nodes.add(new DesiredNode(nodeId, DungeonNodeTypes.TRAP, spec, HumanGating.NONE));

            // Add dependency to room
            dependencies.add(new Dependency(nodeId, NodeId.of(trap.roomDep())));
        }

        return CompilationResult.single(factory.of(nodes, dependencies));
    }
}
