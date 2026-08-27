package io.casehub.desiredstate.yaml.model;

import io.casehub.desiredstate.api.HumanGating;

import java.util.List;
import java.util.Map;

public record YamlNode(
        String type,
        Map<String, Object> spec,
        List<String> dependsOn,
        HumanGating humanGating) {

    public YamlNode {
        if (spec == null) spec = Map.of();
        if (dependsOn == null) dependsOn = List.of();
        if (humanGating == null) humanGating = HumanGating.NONE;
    }
}
