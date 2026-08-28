package io.casehub.desiredstate.yaml.model;

import java.util.List;
import java.util.Map;

public record YamlGraph(
        YamlDesiredState desiredState,
        Map<String, String> variables,
        Map<String, YamlNode> nodes,
        List<YamlFaultPolicy> faultPolicy,
        Map<String, YamlInvariant> invariants,
        Map<String, YamlRule> rules,
        YamlLifecycle lifecycle) {

    public YamlGraph {
        if (variables == null) {variables = Map.of();}
        if (nodes == null) {nodes = Map.of();}
        if (faultPolicy == null) {faultPolicy = List.of();}
        if (invariants == null) {invariants = Map.of();}
        if (rules == null) {rules = Map.of();}
    }
}
