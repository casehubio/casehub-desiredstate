package io.casehub.desiredstate.yaml.model;

import io.casehub.yaml.core.foreach.IterationGroup;
import io.casehub.yaml.core.module.YamlImport;

import java.util.List;
import java.util.Map;

public record YamlGraph(
        YamlDesiredState desiredState,
        Map<String, Object> variables,
        Map<String, YamlNode> nodes,
        List<YamlFaultPolicy> faultPolicy,
        Map<String, YamlInvariant> invariants,
        Map<String, YamlRule> rules,
        YamlLifecycle lifecycle,
        Map<String, IterationGroup> iterations,
        List<YamlImport> imports,
        Map<String, Object> data,
        List<YamlOrderingConstraint> orderingConstraints) {

    public YamlGraph {
        if (variables == null) {variables = Map.of();}
        if (nodes == null) {nodes = Map.of();}
        if (faultPolicy == null) {faultPolicy = List.of();}
        if (invariants == null) {invariants = Map.of();}
        if (rules == null) {rules = Map.of();}
        if (iterations == null) {iterations = Map.of();}
        if (imports == null) {imports = List.of();}
        if (data == null) {data = Map.of();}
        if (orderingConstraints == null) {orderingConstraints = List.of();}
    }
}
