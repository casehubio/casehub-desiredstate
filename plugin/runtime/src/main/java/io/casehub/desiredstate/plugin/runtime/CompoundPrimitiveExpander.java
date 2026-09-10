package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.CompoundPrimitiveDef;
import io.casehub.desiredstate.plugin.model.PluginStepDef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CompoundPrimitiveExpander {

    private final Map<String, CompoundPrimitiveDef> compounds;
    private final Set<String> javaPrimitives;
    private final int maxDepth;

    public CompoundPrimitiveExpander(Map<String, CompoundPrimitiveDef> compounds,
                                     Set<String> javaPrimitives) {
        this(compounds, javaPrimitives, 5);
    }

    public CompoundPrimitiveExpander(Map<String, CompoundPrimitiveDef> compounds,
                                     Set<String> javaPrimitives, int maxDepth) {
        this.compounds = Map.copyOf(compounds);
        this.javaPrimitives = Set.copyOf(javaPrimitives);
        this.maxDepth = maxDepth;
    }

    public List<PluginStepDef> expand(PluginStepDef invocation) {
        return expand(invocation, new HashSet<>(), 0);
    }

    private List<PluginStepDef> expand(PluginStepDef invocation, Set<String> visited,
                                       int depth) {
        String name = invocation.primitiveName();

        if (javaPrimitives.contains(name)) {
            return List.of(invocation);
        }

        CompoundPrimitiveDef compound = compounds.get(name);
        if (compound == null) {
            throw new StepExecutionException("Unknown primitive: " + name);
        }

        if (!visited.add(name)) {
            throw new CyclicPrimitiveException(
                "Cyclic primitive reference: " + name + " → " + visited);
        }

        if (depth >= maxDepth) {
            throw new MaxPrimitiveDepthException(
                "Primitive nesting depth " + (depth + 1) + " exceeds max " + maxDepth
                    + " at primitive '" + name + "'");
        }

        Map<String, Object> paramBindings = invocation.parameters();
        List<PluginStepDef> expanded = new ArrayList<>();

        for (PluginStepDef step : compound.steps()) {
            PluginStepDef bound = bindParameters(step, paramBindings);
            List<PluginStepDef> innerExpanded = expand(bound, new HashSet<>(visited),
                depth + 1);
            expanded.addAll(innerExpanded);
        }

        if (invocation.resultName() != null && compound.resultBinding() != null) {
            if (!expanded.isEmpty()) {
                PluginStepDef last = expanded.get(expanded.size() - 1);
                if (last.resultName() == null
                        || last.resultName().equals(compound.resultBinding())) {
                    expanded.set(expanded.size() - 1,
                        new PluginStepDef(last.primitiveName(), last.parameters(),
                            invocation.resultName(), last.when(), last.onError(),
                            last.maxRetries(), last.backoff()));
                }
            }
        }

        return expanded;
    }

    private PluginStepDef bindParameters(PluginStepDef step,
                                         Map<String, Object> paramBindings) {
        Map<String, Object> boundParams = substituteParams(step.parameters(), paramBindings);
        String boundWhen = step.when() != null
            ? substituteParamString(step.when(), paramBindings) : null;

        return new PluginStepDef(step.primitiveName(), boundParams,
            step.resultName(), boundWhen, step.onError(),
            step.maxRetries(), step.backoff());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> substituteParams(Map<String, Object> params,
                                                 Map<String, Object> bindings) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            result.put(entry.getKey(), substituteValue(entry.getValue(), bindings));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object substituteValue(Object value, Map<String, Object> bindings) {
        if (value instanceof String s) {
            return substituteParamString(s, bindings);
        }
        if (value instanceof Map<?, ?> m) {
            return substituteParams((Map<String, Object>) m, bindings);
        }
        if (value instanceof List<?> l) {
            List<Object> result = new ArrayList<>(l.size());
            for (Object item : l) {
                result.add(substituteValue(item, bindings));
            }
            return result;
        }
        return value;
    }

    private String substituteParamString(String template, Map<String, Object> bindings) {
        String result = template;
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            String placeholder = "${param." + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder,
                    entry.getValue() != null ? entry.getValue().toString() : "null");
            }
        }
        return result;
    }

    public static class CyclicPrimitiveException extends RuntimeException {
        public CyclicPrimitiveException(String message) { super(message); }
    }

    public static class MaxPrimitiveDepthException extends RuntimeException {
        public MaxPrimitiveDepthException(String message) { super(message); }
    }
}
