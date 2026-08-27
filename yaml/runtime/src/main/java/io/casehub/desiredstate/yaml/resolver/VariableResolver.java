package io.casehub.desiredstate.yaml.resolver;

import org.eclipse.microprofile.config.Config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariableResolver {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, String> inlineVariables;
    private final Config config;

    public VariableResolver(Map<String, String> inlineVariables,
                            Object preferences, Config config) {
        this.inlineVariables = inlineVariables != null ? inlineVariables : Map.of();
        this.config = config;
    }

    public Object resolve(Object value) {
        if (value instanceof String s) {
            return s.contains("${") ? resolveString(s, "") : s;
        }
        if (value instanceof Map<?, ?> map) {
            return resolveMap(map, "");
        }
        if (value instanceof List<?> list) {
            return resolveList(list, "");
        }
        return value;
    }

    public String resolveString(String template, String nodeContext) {
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String resolved = lookupVariable(key, nodeContext);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public Map<String, Object> resolveMap(Map<?, ?> input, String nodeContext) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            String key = entry.getKey().toString();
            Object val = entry.getValue();
            if (val instanceof String s && s.contains("${")) {
                result.put(key, resolveString(s, nodeContext));
            } else if (val instanceof Map<?, ?> nested) {
                result.put(key, resolveMap(nested, nodeContext));
            } else if (val instanceof List<?> list) {
                result.put(key, resolveList(list, nodeContext));
            } else {
                result.put(key, val);
            }
        }
        return result;
    }

    public List<?> resolveList(List<?> input, String nodeContext) {
        return input.stream()
                .map(item -> {
                    if (item instanceof String s && s.contains("${")) {
                        return resolveString(s, nodeContext);
                    }
                    return item;
                })
                .toList();
    }

    private String lookupVariable(String key, String nodeContext) {
        String value = inlineVariables.get(key);
        if (value != null) return value;

        if (config != null) {
            Optional<String> configValue = config.getOptionalValue(key, String.class);
            if (configValue.isPresent()) return configValue.get();
        }

        throw new UnresolvedVariableException(key, nodeContext,
                "Not found in: inline variables " + inlineVariables.keySet()
                        + ", MicroProfile Config.");
    }
}
