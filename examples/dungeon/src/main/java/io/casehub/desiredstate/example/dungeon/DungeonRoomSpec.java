package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

/**
 * Specification for a dungeon room: name, description, and size (in tiles).
 */
public record DungeonRoomSpec(String name, String description, int size) implements NodeSpec {

    @Override
    public NodeType nodeType() {return DungeonNodeTypes.ROOM;}
}
