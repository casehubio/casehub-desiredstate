package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads the actual state from {@link DungeonWorld} and translates it to {@link ActualState}.
 */
public class DungeonActualStateAdapter implements ActualStateAdapter {

    private final DungeonWorld world;

    public DungeonActualStateAdapter(DungeonWorld world) {
        this.world = world;
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired) {
        Map<NodeId, NodeStatus> statuses = new HashMap<>();

        // Read room states
        for (Map.Entry<NodeId, DungeonWorld.State> entry : world.allRooms().entrySet()) {
            statuses.put(entry.getKey(), translateRoomState(entry.getValue()));
        }

        // Read creature states
        for (Map.Entry<NodeId, DungeonWorld.State> entry : world.allCreatures().entrySet()) {
            statuses.put(entry.getKey(), translateCreatureState(entry.getValue()));
        }

        // Read trap states
        for (Map.Entry<NodeId, DungeonWorld.State> entry : world.allTraps().entrySet()) {
            statuses.put(entry.getKey(), translateTrapState(entry.getValue()));
        }

        // Check desired nodes not in world — mark as ABSENT
        for (NodeId nodeId : desired.nodes().keySet()) {
            if (!statuses.containsKey(nodeId)) {
                statuses.put(nodeId, NodeStatus.ABSENT);
            }
        }

        return new ActualState(statuses);
    }

    private NodeStatus translateRoomState(DungeonWorld.State state) {
        return switch (state) {
            case BUILT -> NodeStatus.PRESENT;
            case DESTROYED -> NodeStatus.ABSENT;
            case DEGRADED -> NodeStatus.DRIFTED;
            default -> NodeStatus.UNKNOWN;
        };
    }

    private NodeStatus translateCreatureState(DungeonWorld.State state) {
        return switch (state) {
            case PRESENT -> NodeStatus.PRESENT;
            case FLED, DEAD -> NodeStatus.ABSENT;
            default -> NodeStatus.UNKNOWN;
        };
    }

    private NodeStatus translateTrapState(DungeonWorld.State state) {
        return switch (state) {
            case ARMED -> NodeStatus.PRESENT;
            case TRIGGERED -> NodeStatus.ABSENT;
            default -> NodeStatus.UNKNOWN;
        };
    }
}
