package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;

/**
 * Specification for a creature: species and level.
 */
public record CreatureSpec(String species, int level) implements NodeSpec {
}
