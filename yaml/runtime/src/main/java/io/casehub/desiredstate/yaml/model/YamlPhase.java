package io.casehub.desiredstate.yaml.model;

import java.util.Map;

public record YamlPhase(
        String id,
        Object completionCondition,
        Map<String, YamlNode> nodes) {
    public YamlPhase {
        nodes = nodes != null ? nodes : Map.of();
    }
}
