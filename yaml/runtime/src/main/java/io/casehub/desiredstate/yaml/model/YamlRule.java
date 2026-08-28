package io.casehub.desiredstate.yaml.model;

import java.util.List;
import java.util.Map;

public record YamlRule(
        List<String> graph,
        Map<String, YamlPattern> match,
        Map<String, YamlPattern> directDep,
        Map<String, YamlPattern> reaches,
        Map<String, YamlPattern> notExists,
        List<Map<String, Object>> actions) {

    public YamlRule {
        graph = graph != null ? graph : List.of();
        match = match != null ? match : Map.of();
        directDep = directDep != null ? directDep : Map.of();
        reaches = reaches != null ? reaches : Map.of();
        notExists = notExists != null ? notExists : Map.of();
        actions = actions != null ? actions : List.of();
    }
}
