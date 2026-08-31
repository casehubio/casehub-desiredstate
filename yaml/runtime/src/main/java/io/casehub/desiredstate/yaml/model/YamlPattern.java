package io.casehub.desiredstate.yaml.model;

import io.casehub.desiredstate.annotations.runtime.Direction;

public record YamlPattern(String type, String of, Direction direction,
                          Integer minCount, Integer maxCount) {

    public YamlPattern {
        direction = direction != null ? direction : Direction.DEPENDENCIES;
    }

    public YamlPattern(String type, String of, Direction direction) {
        this(type, of, direction, null, null);
    }
}
