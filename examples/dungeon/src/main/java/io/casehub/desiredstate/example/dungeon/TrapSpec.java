package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.NodeSpec;

/**
 * Specification for a trap: type (e.g., "spike", "poison-gas") and damage.
 */
public record TrapSpec(String type, int damage) implements NodeSpec {
}
