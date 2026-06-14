package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable in-memory simulation of the dungeon's actual state.
 * Tracks rooms, creatures, and traps with their current states.
 */
@ApplicationScoped
public class DungeonWorld {

    public enum State {
        // Room states
        BUILT, DESTROYED, DEGRADED,
        // Creature states
        PRESENT, FLED, DEAD,
        // Trap states
        ARMED, TRIGGERED
    }

    private final ConcurrentHashMap<NodeId, State> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, State> creatures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, State> traps = new ConcurrentHashMap<>();

    // Room operations
    public void setRoom(NodeId id, State state) {
        rooms.put(id, state);
    }

    public State roomState(NodeId id) {
        return rooms.get(id);
    }

    public void destroyRoom(NodeId id) {
        rooms.put(id, State.DESTROYED);
    }

    public Map<NodeId, State> allRooms() {
        return Map.copyOf(rooms);
    }

    // Creature operations
    public void setCreature(NodeId id, State state) {
        creatures.put(id, state);
    }

    public State creatureState(NodeId id) {
        return creatures.get(id);
    }

    public void removeCreature(NodeId id) {
        creatures.remove(id);
    }

    public Map<NodeId, State> allCreatures() {
        return Map.copyOf(creatures);
    }

    // Trap operations
    public void setTrap(NodeId id, State state) {
        traps.put(id, state);
    }

    public State trapState(NodeId id) {
        return traps.get(id);
    }

    public Map<NodeId, State> allTraps() {
        return Map.copyOf(traps);
    }
}
