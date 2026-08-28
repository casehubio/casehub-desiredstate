package io.casehub.desiredstate.yaml.model;

import java.util.List;
import java.util.Map;

public record YamlInvariant(
        List<String> graph,
        Map<String, YamlPattern> match,
        Map<String, YamlPattern> directDep,
        Map<String, YamlPattern> reaches,
        Map<String, YamlPattern> notExists,
        String message) {

    public YamlInvariant {
        graph = graph != null ? graph : List.of();
        match = match != null ? match : Map.of();
        directDep = directDep != null ? directDep : Map.of();
        reaches = reaches != null ? reaches : Map.of();
        notExists = notExists != null ? notExists : Map.of();
    }
}
