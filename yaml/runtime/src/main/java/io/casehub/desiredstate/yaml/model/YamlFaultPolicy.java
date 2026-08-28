package io.casehub.desiredstate.yaml.model;

import java.util.List;

public record YamlFaultPolicy(
        List<String> faultTypes,
        List<String> nodeTypes,
        List<String> ignoreTypes,
        String namespace,
        List<YamlFaultTier> tiers) {

    public YamlFaultPolicy {
        faultTypes = faultTypes != null ? faultTypes : List.of();
        nodeTypes = nodeTypes != null ? nodeTypes : List.of();
        ignoreTypes = ignoreTypes != null ? ignoreTypes : List.of();
        tiers = tiers != null ? tiers : List.of();
    }
}
