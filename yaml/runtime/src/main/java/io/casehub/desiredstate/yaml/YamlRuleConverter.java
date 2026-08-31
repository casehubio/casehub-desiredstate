package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import io.casehub.desiredstate.annotations.runtime.MatchTemplateResolver;
import io.casehub.desiredstate.annotations.runtime.PatternKind;
import io.casehub.desiredstate.annotations.runtime.PatternParameterDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedRule;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.yaml.model.YamlPattern;
import io.casehub.desiredstate.yaml.model.YamlRule;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import io.casehub.desiredstate.yaml.resolver.VariableResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlRuleConverter {

    private YamlRuleConverter() {}

    public static ResolvedRule.DeclarativeRule toDeclarativeRule(
            String name, YamlRule yamlRule,
            VariableResolver resolver, NodeSpecRegistry registry) {

        List<PatternParameterDescriptor> patterns = new ArrayList<>();
        List<String> bindingNamesList = new ArrayList<>();

        for (Map.Entry<String, YamlPattern> entry : yamlRule.match().entrySet()) {
            YamlPattern p = entry.getValue();
            patterns.add(new PatternParameterDescriptor(
                    PatternKind.MATCH, p.type(),
                    p.of() != null ? p.of() : "", p.direction()));
            bindingNamesList.add(entry.getKey());
        }

        addPatterns(yamlRule.directDep(), PatternKind.DIRECT_DEP, patterns, bindingNamesList);
        addPatterns(yamlRule.reaches(), PatternKind.REACHES, patterns, bindingNamesList);
        addPatterns(yamlRule.notExists(), PatternKind.NOT_EXISTS, patterns, bindingNamesList);

        String[] bindingNames = bindingNamesList.toArray(String[]::new);

        List<Map<String, Object>> resolvedActions = new ArrayList<>();
        for (Map<String, Object> action : yamlRule.actions()) {
            resolvedActions.add(resolveVarInAction(action, resolver, name));
        }

        ObjectMapper coercionMapper = createCoercionMapper();

        return new ResolvedRule.DeclarativeRule(name, patterns, bindingNames,
                bindings -> evaluateActions(resolvedActions, bindings, registry,
                        coercionMapper, name));
    }

    @SuppressWarnings("unchecked")
    private static List<GraphMutation> evaluateActions(
            List<Map<String, Object>> actions,
            Map<String, DesiredNode> bindings,
            NodeSpecRegistry registry,
            ObjectMapper mapper,
            String ruleName) {

        List<GraphMutation> mutations = new ArrayList<>();

        for (Map<String, Object> action : actions) {
            String actionType = action.keySet().iterator().next();
            Map<String, Object> params = (Map<String, Object>) action.get(actionType);

            switch (actionType) {
                case "addNode" -> {
                    String id = MatchTemplateResolver.resolveNodeId(
                            (String) params.get("id"), bindings, ruleName);
                    String type = MatchTemplateResolver.resolve(
                            (String) params.get("type"), bindings);
                    Map<String, Object> specMap = params.containsKey("spec")
                            ? resolveMatchInMap((Map<String, Object>) params.get("spec"), bindings)
                            : Map.of();
                    NodeSpec spec = registry.resolve(type).create(specMap);
                    HumanGating gating = params.containsKey("humanGating")
                            ? HumanGating.valueOf((String) params.get("humanGating"))
                            : HumanGating.NONE;
                    mutations.add(new GraphMutation.AddNode(
                            new DesiredNode(NodeId.of(id), spec, gating)));
                }
                case "removeNode" -> {
                    String id = MatchTemplateResolver.resolveNodeId(
                            (String) params.get("id"), bindings, ruleName);
                    mutations.add(new GraphMutation.RemoveNode(NodeId.of(id)));
                }
                case "updateNode" -> {
                    String id = MatchTemplateResolver.resolveNodeId(
                            (String) params.get("id"), bindings, ruleName);
                    String type = MatchTemplateResolver.resolve(
                            (String) params.get("type"), bindings);
                    Map<String, Object> specMap = params.containsKey("spec")
                            ? resolveMatchInMap((Map<String, Object>) params.get("spec"), bindings)
                            : Map.of();
                    NodeSpec spec = registry.resolve(type).create(specMap);
                    HumanGating gating = params.containsKey("humanGating")
                            ? HumanGating.valueOf((String) params.get("humanGating"))
                            : HumanGating.NONE;
                    mutations.add(new GraphMutation.UpdateNode(NodeId.of(id),
                            new DesiredNode(NodeId.of(id), spec, gating)));
                }
                case "addDependency" -> {
                    String from = MatchTemplateResolver.resolve(
                            (String) params.get("from"), bindings);
                    String to = MatchTemplateResolver.resolve(
                            (String) params.get("to"), bindings);
                    mutations.add(new GraphMutation.AddDependency(
                            new Dependency(NodeId.of(from), NodeId.of(to))));
                }
                case "removeDependency" -> {
                    String from = MatchTemplateResolver.resolve(
                            (String) params.get("from"), bindings);
                    String to = MatchTemplateResolver.resolve(
                            (String) params.get("to"), bindings);
                    mutations.add(new GraphMutation.RemoveDependency(
                            new Dependency(NodeId.of(from), NodeId.of(to))));
                }
                default -> throw new IllegalArgumentException(
                        "Unknown action type: " + actionType);
            }
        }
        return mutations;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveMatchInMap(
            Map<String, Object> input, Map<String, DesiredNode> bindings) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s) {
                result.put(entry.getKey(), MatchTemplateResolver.resolve(s, bindings));
            } else if (val instanceof Map<?, ?> nested) {
                result.put(entry.getKey(),
                        resolveMatchInMap((Map<String, Object>) nested, bindings));
            } else {
                result.put(entry.getKey(), val);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveVarInAction(
            Map<String, Object> action, VariableResolver resolver, String context) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : action.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map<?, ?> nested) {
                result.put(entry.getKey(),
                        resolveVarInActionParams((Map<String, Object>) nested, resolver, context));
            } else {
                result.put(entry.getKey(), val);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveVarInActionParams(
            Map<String, Object> params, VariableResolver resolver, String context) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s && s.contains("${var.")) {
                result.put(entry.getKey(), resolver.resolveTemplateString(s, context));
            } else if (val instanceof Map<?, ?> nested) {
                result.put(entry.getKey(),
                        resolveVarInActionParams((Map<String, Object>) nested, resolver, context));
            } else {
                result.put(entry.getKey(), val);
            }
        }
        return result;
    }

    private static void addPatterns(Map<String, YamlPattern> section, PatternKind kind,
            List<PatternParameterDescriptor> patterns, List<String> bindingNames) {
        for (Map.Entry<String, YamlPattern> entry : section.entrySet()) {
            YamlPattern p = entry.getValue();
            patterns.add(new PatternParameterDescriptor(
                    kind, p.type(),
                    p.of() != null ? p.of() : "", p.direction()));
            bindingNames.add(entry.getKey());
        }
    }

    private static ObjectMapper createCoercionMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.coercionConfigDefaults()
                .setCoercion(CoercionInputShape.String, CoercionAction.TryConvert);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
