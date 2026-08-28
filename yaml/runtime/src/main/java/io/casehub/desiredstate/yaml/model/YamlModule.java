package io.casehub.desiredstate.yaml.model;

import java.util.Map;

public record YamlModule(
        String name,
        Map<String, YamlModuleParameter> parameters,
        Map<String, YamlNode> nodes,
        Map<String, YamlRule> rules,
        Map<String, YamlInvariant> invariants) {

    public YamlModule {
        if (parameters == null) {parameters = Map.of();}
        if (nodes == null) {nodes = Map.of();}
        if (rules == null) {rules = Map.of();}
        if (invariants == null) {invariants = Map.of();}
    }
}
