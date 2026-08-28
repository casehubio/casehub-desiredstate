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

    public String resolveTemplateString(String template, String nodeContext) {
        Matcher       matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb      = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.startsWith("var.")) {
                String resolved = lookupVarPrefixed(key.substring(4), nodeContext);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveTemplateMap(Map<?, ?> input, String nodeContext) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            String key = entry.getKey().toString();
            Object val = entry.getValue();
            if (val instanceof String s && s.contains("${")) {
                result.put(key, resolveTemplateString(s, nodeContext));
            } else if (val instanceof Map<?, ?> nested) {
                result.put(key, resolveTemplateMap(nested, nodeContext));
            } else if (val instanceof List<?> list) {
                result.put(key, resolveTemplateList(list, nodeContext));
            } else {
                result.put(key, val);
            }
        }
        return result;
    }

    public List<?> resolveTemplateList(List<?> input, String nodeContext) {
        return input.stream()
                    .map(item -> {
                        if (item instanceof String s && s.contains("${")) {
                            return resolveTemplateString(s, nodeContext);
                        }
                        return item;
                    })
                    .toList();
    }


    private String lookupVariable(String key, String nodeContext) {
        if (key.startsWith("var.")) {
            String varName = key.substring(4);
            return lookupVarPrefixed(varName, nodeContext);
        }
        if (key.startsWith("match.")) {
            throw new UnresolvedVariableException(key, nodeContext,
                                                  "${match.*} references are resolved at rule evaluation time, "
                                                  + "not during variable resolution. This placeholder will be "
                                                  + "resolved by the DeclarativeRuleAdapter.");
        }
        if (key.startsWith("fault.")) {
            throw new UnresolvedVariableException(key, nodeContext,
                                                  "${fault.*} references are resolved at fault time, "
                                                  + "not during variable resolution. This placeholder will be "
                                                  + "resolved by the fault policy template factory.");
        }
        if (key.startsWith("each.")) {
            throw new UnresolvedVariableException(key, nodeContext,
                                                  "${each.*} references are resolved during forEach expansion, "
                                                  + "not during variable resolution.");
        }
        // Bare name — no prefix
        throw new UnresolvedVariableException(key, nodeContext,
                                              "Bare variable references are no longer supported. "
                                              + "Use ${var." + key + "} instead of ${" + key + "}.");
    }

    private String lookupVarPrefixed(String varName, String nodeContext) {
        String value = inlineVariables.get(varName);
        if (value != null) {return value;}

        if (config != null) {
            Optional<String> configValue = config.getOptionalValue(varName, String.class);
            if (configValue.isPresent()) {return configValue.get();}
        }

        throw new UnresolvedVariableException("var." + varName, nodeContext,
                                              "Not found in: inline variables " + inlineVariables.keySet()
                                              + ", MicroProfile Config.");
    }

}
