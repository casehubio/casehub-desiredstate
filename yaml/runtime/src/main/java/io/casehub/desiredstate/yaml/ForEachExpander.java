package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.yaml.model.YamlIterationGroup;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import io.casehub.desiredstate.yaml.resolver.VariableResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ForEachExpander {

    public record ExpandedNodes(
            List<DesiredNode> nodes,
            List<Dependency> dependencies,
            Set<String> excludedNodeIds) {}

    private ForEachExpander() {}

    @SuppressWarnings("unchecked")
    public static ExpandedNodes expand(
            Map<String, YamlNode> yamlNodes,
            Map<String, YamlIterationGroup> iterationGroups,
            VariableResolver resolver,
            NodeSpecRegistry registry,
            ObjectMapper mapper,
            int maxExpansion) {
        return expand(yamlNodes, iterationGroups, resolver, registry, mapper, maxExpansion, Map.of());
    }

    @SuppressWarnings("unchecked")
    public static ExpandedNodes expand(
            Map<String, YamlNode> yamlNodes,
            Map<String, YamlIterationGroup> iterationGroups,
            VariableResolver resolver,
            NodeSpecRegistry registry,
            ObjectMapper mapper,
            int maxExpansion,
            Map<String, Map<String, String>> moduleScopes) {

        List<DesiredNode> allNodes = new ArrayList<>();
        List<Dependency> allDeps = new ArrayList<>();
        Set<String> excludedNodeIds = new HashSet<>();
        Set<String> forEachTemplateIds = new HashSet<>();
        Map<String, String> nodeToGroup = new LinkedHashMap<>();
        Map<String, List<String>> groupValues = new LinkedHashMap<>();

        for (Map.Entry<String, YamlNode> entry : yamlNodes.entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            Object forEach = yamlNode.forEach();

            if (forEach == null) {
                nodeToGroup.put(nodeId, null);
                continue;
            }

            forEachTemplateIds.add(nodeId);
            List<String> values;
            String groupKey;

            if (forEach instanceof String groupRef) {
                groupKey = groupRef;
                if (!groupValues.containsKey(groupRef)) {
                    YamlIterationGroup group = iterationGroups.get(groupRef);
                    values = resolveGroupValues(group.inAsList(), resolver, groupRef, mapper);
                    groupValues.put(groupRef, values);
                }
                values = groupValues.get(groupRef);
            } else if (forEach instanceof Map<?, ?> inlineMap) {
                groupKey = "__inline__" + nodeId;
                List<?> in = (List<?>) inlineMap.get("in");
                for (Object item : in) {
                    if (!(item instanceof String)) {
                        throw new IllegalArgumentException("forEach template '" + nodeId
                                + "': forEach values must be strings, got "
                                + item.getClass().getSimpleName() + " (" + item + ")");
                    }
                }
                values = in.stream().map(o -> (String) o).toList();
                groupValues.put(groupKey, values);
            } else {
                throw new IllegalArgumentException("Invalid forEach on node '" + nodeId + "'");
            }

            nodeToGroup.put(nodeId, groupKey);

            if (values.size() > maxExpansion) {
                throw new IllegalStateException("forEach template '" + nodeId
                        + "' would expand to " + values.size() + " nodes (limit: "
                        + maxExpansion + "). Configure "
                        + "casehub.desiredstate.foreach.max-expansion to raise the limit.");
            }
        }

        for (Map.Entry<String, YamlNode> entry : yamlNodes.entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String groupKey = nodeToGroup.get(nodeId);

            if (groupKey == null) {
                VariableResolver nodeResolver = resolverForNode(resolver, nodeId, moduleScopes);
                if (yamlNode.when() != null) {
                    String resolvedWhen = nodeResolver.resolveString(yamlNode.when(), nodeId);
                    if (!isTruthy(resolvedWhen)) {
                        excludedNodeIds.add(nodeId);
                        continue;
                    }
                }
                Map<String, Object> resolvedSpec = nodeResolver.resolveMap(
                        yamlNode.spec(), nodeId);
                NodeSpec spec = registry.resolve(yamlNode.type()).create(resolvedSpec);
                allNodes.add(new DesiredNode(NodeId.of(nodeId), spec,
                        yamlNode.humanGating(), HookResolver.resolveHooks(yamlNode, nodeResolver, nodeId)));
                continue;
            }

            List<String> values = groupValues.get(groupKey);
            String as = resolveAs(yamlNode.forEach(), iterationGroups);

            for (String value : values) {
                String stampedId = nodeId + "." + value;
                VariableResolver eachResolver = resolverForNode(resolver, nodeId, moduleScopes)
                        .withEachContext(Map.of(as, value));

                if (yamlNode.when() != null) {
                    String resolvedWhen = eachResolver.resolveString(
                            yamlNode.when(), stampedId);
                    if (!isTruthy(resolvedWhen)) {
                        excludedNodeIds.add(stampedId);
                        continue;
                    }
                }

                Map<String, Object> resolvedSpec = eachResolver.resolveMap(
                        yamlNode.spec(), stampedId);
                NodeSpec spec = registry.resolve(yamlNode.type()).create(resolvedSpec);
                allNodes.add(new DesiredNode(NodeId.of(stampedId), spec,
                        yamlNode.humanGating(), HookResolver.resolveHooks(yamlNode, eachResolver, stampedId)));
            }
        }

        for (Map.Entry<String, YamlNode> entry : yamlNodes.entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String groupKey = nodeToGroup.get(nodeId);

            for (Object dep : yamlNode.dependsOn()) {
                String rawDepId = YamlNode.dependencyNodeId(dep);
                boolean optional = YamlNode.isDependencyOptional(dep);

                String depId = rawDepId;
                if (rawDepId.contains("${")) {
                    VariableResolver depResolver = resolverForNode(resolver, nodeId, moduleScopes);
                    depId = depResolver.resolveString(rawDepId, nodeId);
                }
                String depGroup = nodeToGroup.get(depId);

                if (groupKey == null && depGroup == null) {
                    if (excludedNodeIds.contains(nodeId) || excludedNodeIds.contains(depId)) {
                        if (excludedNodeIds.contains(depId) && !excludedNodeIds.contains(nodeId) && !optional) {
                            throw new IllegalStateException("Node '" + nodeId
                                    + "' depends on excluded conditional node '" + depId + "'");
                        }
                        continue;
                    }
                    allDeps.add(new Dependency(NodeId.of(nodeId), NodeId.of(depId)));
                } else if (groupKey != null && depGroup == null) {
                    List<String> values = groupValues.get(groupKey);
                    for (String value : values) {
                        String stampedFrom = nodeId + "." + value;
                        if (excludedNodeIds.contains(stampedFrom) || excludedNodeIds.contains(depId)) {
                            continue;
                        }
                        allDeps.add(new Dependency(NodeId.of(stampedFrom), NodeId.of(depId)));
                    }
                } else if (groupKey != null && groupKey.equals(depGroup)) {
                    List<String> values = groupValues.get(groupKey);
                    for (String value : values) {
                        String stampedFrom = nodeId + "." + value;
                        String stampedTo = depId + "." + value;
                        if (excludedNodeIds.contains(stampedFrom) || excludedNodeIds.contains(stampedTo)) {
                            continue;
                        }
                        allDeps.add(new Dependency(NodeId.of(stampedFrom), NodeId.of(stampedTo)));
                    }
                } else if (groupKey == null && depGroup != null) {
                    if (!optional) {
                        throw new IllegalStateException("Node '" + nodeId
                                + "' depends on forEach template '" + depId + "'");
                    }
                }
            }
        }

        return new ExpandedNodes(allNodes, allDeps, excludedNodeIds);
    }

    private static VariableResolver resolverForNode(VariableResolver base, String nodeId,
            Map<String, Map<String, String>> moduleScopes) {
        if (moduleScopes.isEmpty()) {return base;}
        int dot = nodeId.indexOf('.');
        if (dot < 0) {return base;}
        String prefix = nodeId.substring(0, dot);
        Map<String, String> scope = moduleScopes.get(prefix);
        return scope != null ? base.withModuleScope(scope) : base;
    }

    @SuppressWarnings("unchecked")
    private static String resolveAs(Object forEach,
            Map<String, YamlIterationGroup> groups) {
        if (forEach instanceof String groupRef) {
            return groups.get(groupRef).as();
        }
        if (forEach instanceof Map<?, ?> m) {
            return (String) m.get("as");
        }
        throw new IllegalArgumentException("Invalid forEach: " + forEach);
    }

    private static List<String> resolveGroupValues(List<Object> in,
            VariableResolver resolver, String groupRef, ObjectMapper mapper) {
        if (in.size() == 1 && in.get(0) instanceof String s && s.contains("${")) {
            String resolved = resolver.resolveString(s, "iterations." + groupRef);
            return parseJsonArray(resolved, groupRef, mapper);
        }
        return in.stream().map(Object::toString).toList();
    }

    private static boolean isTruthy(String value) {
        return YamlGraphRecorder.isTruthy(value);
    }

    private static List<String> parseJsonArray(String json, String groupRef, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("forEach group '" + groupRef
                    + "': Use [] for an empty array, not an empty string");
        }
        try {
            List<?> parsed = mapper.readValue(json, new TypeReference<List<?>>() {});
            List<String> result = new ArrayList<>();
            for (Object item : parsed) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException("forEach group '" + groupRef
                            + "': forEach values must be strings, got "
                            + item.getClass().getSimpleName());
                }
                result.add((String) item);
            }
            return result;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("forEach group '" + groupRef
                    + "': variable resolved to '" + json
                    + "' which is not a valid JSON array. "
                    + "Expected a JSON array of strings like [\"a\", \"b\"].", e);
        }
    }
}
