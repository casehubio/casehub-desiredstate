package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;

/**
 * Specification for a dungeon room: name, description, and size (in tiles).
 */
public record DungeonRoomSpec(String name, String description, int size) implements NodeSpec {
}
