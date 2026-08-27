package io.casehub.desiredstate.yaml.model;

import java.util.Map;

public record YamlGraph(
        YamlDesiredState desiredState,
        Map<String, String> variables,
        Map<String, YamlNode> nodes) {

    public YamlGraph {
        if (variables == null) variables = Map.of();
        if (nodes == null) nodes = Map.of();
    }
}
