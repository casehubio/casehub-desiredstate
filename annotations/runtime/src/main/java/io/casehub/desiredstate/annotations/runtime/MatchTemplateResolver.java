package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MatchTemplateResolver {

    private static final Pattern MATCH_PATTERN =
            Pattern.compile("\\$\\{match\\.([^.]+)\\.(id|type|flatId)}");

    private MatchTemplateResolver() {}

    public static String resolve(String template, Map<String, DesiredNode> bindings) {
        Matcher matcher = MATCH_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String bindingName = matcher.group(1);
            String accessor = matcher.group(2);
            DesiredNode node = bindings.get(bindingName);
            if (node == null) {
                throw new IllegalArgumentException(
                        "Unknown binding '" + bindingName + "' in template '" + template
                        + "'. Available: " + bindings.keySet());
            }
            String value = switch (accessor) {
                case "id" -> node.id().value();
                case "type" -> node.type().value();
                case "flatId" -> node.id().value().replace('.', '-');
                default -> throw new IllegalStateException("Unexpected accessor: " + accessor);
            };
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public static String resolveNodeId(String template, Map<String, DesiredNode> bindings,
                                        String ruleName) {
        String resolved = resolve(template, bindings);
        if (resolved.contains(".")) {
            throw new IllegalArgumentException(
                    "Rule '" + ruleName + "' produces node ID '" + resolved
                    + "' which contains the reserved '.' separator. "
                    + "Rule-generated node IDs must not contain '.'. "
                    + "Use ${match.*.flatId} to replace '.' with '-'.");
        }
        return resolved;
    }
}
