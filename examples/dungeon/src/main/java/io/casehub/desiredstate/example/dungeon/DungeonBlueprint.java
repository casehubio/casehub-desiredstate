package io.casehub.desiredstate.example.dungeon;

import java.util.ArrayList;
import java.util.List;

/**
 * Goal declaration for a dungeon: defines rooms, creatures, and traps to be built.
 * Use the Builder to construct a blueprint.
 */
public class DungeonBlueprint {

    public record RoomEntry(String id, String description, int size) {
    }

    public record CreatureEntry(String id, String species, int level, List<String> roomDeps, boolean requiresHuman) {
    }

    public record TrapEntry(String id, String type, int damage, String roomDep) {
    }

    private final List<RoomEntry> rooms;
    private final List<CreatureEntry> creatures;
    private final List<TrapEntry> traps;

    private DungeonBlueprint(List<RoomEntry> rooms, List<CreatureEntry> creatures, List<TrapEntry> traps) {
        this.rooms = List.copyOf(rooms);
        this.creatures = List.copyOf(creatures);
        this.traps = List.copyOf(traps);
    }

    public List<RoomEntry> rooms() {
        return rooms;
    }

    public List<CreatureEntry> creatures() {
        return creatures;
    }

    public List<TrapEntry> traps() {
        return traps;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<RoomEntry> rooms = new ArrayList<>();
        private final List<CreatureEntry> creatures = new ArrayList<>();
        private final List<TrapEntry> traps = new ArrayList<>();

        public Builder room(String id, String description, int size) {
            rooms.add(new RoomEntry(id, description, size));
            return this;
        }

        public Builder creature(String id, String species, int level, String... roomDeps) {
            creatures.add(new CreatureEntry(id, species, level, List.of(roomDeps), false));
            return this;
        }

        public Builder humanCreature(String id, String species, int level, String... roomDeps) {
            creatures.add(new CreatureEntry(id, species, level, List.of(roomDeps), true));
            return this;
        }

        public Builder trap(String id, String type, int damage, String roomDep) {
            traps.add(new TrapEntry(id, type, damage, roomDep));
            return this;
        }

        public DungeonBlueprint build() {
            return new DungeonBlueprint(rooms, creatures, traps);
        }
    }
}
