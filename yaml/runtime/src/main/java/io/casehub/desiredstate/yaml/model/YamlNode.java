package io.casehub.desiredstate.yaml.model;

import io.casehub.desiredstate.api.HumanGating;

import java.util.List;
import java.util.Map;

public record YamlNode(
        String type,
        Map<String, Object> spec,
        List<Object> dependsOn,
        HumanGating humanGating,
        String when,
        Object forEach) {

    public YamlNode {
        if (spec == null) {spec = Map.of();}
        if (dependsOn == null) {dependsOn = List.of();}
        if (humanGating == null) {humanGating = HumanGating.NONE;}
    }

    @SuppressWarnings("unchecked")
    public static String dependencyNodeId(Object dep) {
        if (dep instanceof String s) {return s;}
        if (dep instanceof Map<?, ?> m) {return (String) m.get("node");}
        throw new IllegalArgumentException("Invalid dependency format: " + dep);
    }

    @SuppressWarnings("unchecked")
    public static boolean isDependencyOptional(Object dep) {
        if (dep instanceof String) {return false;}
        if (dep instanceof Map<?, ?> m) {return Boolean.TRUE.equals(m.get("optional"));}
        return false;
    }

    public List<String> dependencyNodeIds() {
        return dependsOn.stream().map(YamlNode::dependencyNodeId).toList();
    }
}
