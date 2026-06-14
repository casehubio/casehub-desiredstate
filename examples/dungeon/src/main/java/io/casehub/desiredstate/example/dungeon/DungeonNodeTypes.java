package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeType;

/**
 * NodeType constants for the Nefarious Dungeons domain.
 */
public final class DungeonNodeTypes {
    public static final NodeType ROOM = new NodeType("room");
    public static final NodeType CREATURE = new NodeType("creature");
    public static final NodeType TRAP = new NodeType("trap");

    private DungeonNodeTypes() {
    }
}
