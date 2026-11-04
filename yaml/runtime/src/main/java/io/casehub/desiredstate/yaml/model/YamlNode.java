package io.casehub.desiredstate.yaml.model;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.TargetStatus;

import java.util.List;
import java.util.Map;

public record YamlNode(
        String type,
        Map<String, Object> spec,
        List<Object> dependsOn,
        HumanGating humanGating,
        String when,
        Object forEach,
        YamlHooks provision,
        YamlHooks deprovision,
        YamlHooks suspend,
        YamlHooks resume,
        TargetStatus targetStatus) {

    public YamlNode(String type, Map<String, Object> spec, List<Object> dependsOn,
                    HumanGating humanGating, String when, Object forEach,
                    YamlHooks provision, YamlHooks deprovision,
                    YamlHooks suspend, YamlHooks resume) {
        this(type, spec, dependsOn, humanGating, when, forEach,
             provision, deprovision, suspend, resume, null);
    }

    public YamlNode(String type, Map<String, Object> spec, List<Object> dependsOn,
                    HumanGating humanGating, String when, Object forEach,
                    YamlHooks provision, YamlHooks deprovision) {
        this(type, spec, dependsOn, humanGating, when, forEach,
             provision, deprovision, null, null, null);
    }

    public YamlNode {
        if (spec == null) {spec = Map.of();}
        if (dependsOn == null) {dependsOn = List.of();}
        if (humanGating == null) {humanGating = HumanGating.NONE;}
        if (targetStatus == null) {targetStatus = TargetStatus.ACTIVE;}
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
