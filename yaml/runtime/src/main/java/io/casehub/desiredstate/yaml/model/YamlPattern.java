package io.casehub.desiredstate.yaml.model;

import io.casehub.desiredstate.annotations.runtime.Direction;

public record YamlPattern(String type, String of, Direction direction) {

    public YamlPattern {
        direction = direction != null ? direction : Direction.DEPENDENCIES;
    }
}
