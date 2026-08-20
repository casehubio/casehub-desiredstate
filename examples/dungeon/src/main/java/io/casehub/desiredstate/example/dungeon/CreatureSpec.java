package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record CreatureSpec(String species, int level, HumanGating humanGating) implements NodeSpec {

    public CreatureSpec(String species, int level) {
        this(species, level, HumanGating.NONE);
    }

    @Override
    public HumanGating humanGating() {
        return humanGating;
    }

    @Override
    public NodeType nodeType() {return DungeonNodeTypes.CREATURE;}
}
