package io.casehub.desiredstate.plugin.api;

import io.casehub.desiredstate.plugin.api.expr.ExpressionEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginInterpolator {

    private static final Pattern REF_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final java.util.Set<String> KNOWN_PREFIXES =
        java.util.Set.of("spec", "auth", "result", "param", "var", "fault", "each", "match");

    public String interpolate(String template, StepContext context) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        Matcher m = REF_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            validatePrefix(ref);
            Object resolved = context.resolve(ref);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                resolved != null ? resolved.toString() : "null"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public boolean evaluateCondition(String condition, StepContext context) {
        String interpolated = interpolate(condition, context);
        Map<String, Object> emptyBindings = Map.of();
        return ExpressionEvaluator.evaluate(interpolated, emptyBindings);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> interpolateMap(Map<String, Object> template, StepContext context) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            result.put(entry.getKey(), interpolateValue(entry.getValue(), context));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object interpolateValue(Object value, StepContext context) {
        if (value instanceof String s) {
            return interpolate(s, context);
        }
        if (value instanceof Map<?, ?> m) {
            return interpolateMap((Map<String, Object>) m, context);
        }
        if (value instanceof List<?> l) {
            List<Object> result = new ArrayList<>(l.size());
            for (Object item : l) {
                result.add(interpolateValue(item, context));
            }
            return result;
        }
        return value;
    }

    private void validatePrefix(String ref) {
        int dot = ref.indexOf('.');
        if (dot < 0) {
            throw new InterpolationException(
                "Reference must use a prefix (e.g., ${spec.field}): ${" + ref + "}");
        }
        String prefix = ref.substring(0, dot);
        if (!KNOWN_PREFIXES.contains(prefix)) {
            throw new InterpolationException(
                "Unknown prefix '" + prefix + "' in reference ${" + ref
                    + "}. Known prefixes: " + KNOWN_PREFIXES);
        }
    }
}
