package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.PluginFieldDef;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.yaml.step.catalog.ResolvedStep;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PluginValidator {

    public static final Set<String> BUILT_IN_PRIMITIVES = Set.of(
        "rest-call", "graphql-call", "json-extract", "compare-state", "assert", "approval-gate");

    public static final Set<String> SUPPORTED_FIELD_TYPES = Set.of(
        "string", "integer", "number", "boolean", "enum", "list", "map");

    static final Pattern INTERPOLATION_REF = Pattern.compile("\\$\\{([^}]+)}");

    static final Set<String> KNOWN_PREFIXES = Set.of(
        "spec", "auth", "result", "param", "var", "fault");

    private PluginValidator() {}

    public static void validatePlugin(PluginModel plugin, Map<String, String> typeRegistry,
                                       Set<String> knownPrimitives) {
        String type = plugin.header().type();

        if (typeRegistry.containsKey(type)) {
            throw new PluginValidationException(type,
                "Type conflict — Java @NodeTypeId(\"" + type + "\") already declares this type via "
                    + typeRegistry.get(type));
        }

        validateSpecSchema(type, plugin.spec());
        validateSteps(type, "actual-state", plugin.actualStateSteps(),
            plugin.spec(), knownPrimitives);
        validateSteps(type, "provisioner.provision", plugin.provisioner().provisionSteps(),
            plugin.spec(), knownPrimitives);
        validateSteps(type, "provisioner.deprovision", plugin.provisioner().deprovisionSteps(),
            plugin.spec(), knownPrimitives);
        validateActualStateHasCompareState(type, plugin.actualStateSteps());
        validateActualStateNoApprovalGate(type, plugin.actualStateSteps());
    }

    public static void validateSpecSchema(String type, PluginSpecSchema spec) {
        for (Map.Entry<String, PluginFieldDef> entry : spec.fields().entrySet()) {
            String fieldName = entry.getKey();
            PluginFieldDef field = entry.getValue();

            if (!SUPPORTED_FIELD_TYPES.contains(field.type())) {
                throw new PluginValidationException(type,
                    "spec.fields." + fieldName + ": unsupported type '" + field.type()
                        + "'. Supported: " + SUPPORTED_FIELD_TYPES);
            }

            if (field.min() != null && field.max() != null
                    && field.min().doubleValue() > field.max().doubleValue()) {
                throw new PluginValidationException(type,
                    "spec.fields." + fieldName + ": min (" + field.min()
                        + ") > max (" + field.max() + ")");
            }

            if (field.minLength() != null && field.maxLength() != null
                    && field.minLength() > field.maxLength()) {
                throw new PluginValidationException(type,
                    "spec.fields." + fieldName + ": minLength (" + field.minLength()
                        + ") > maxLength (" + field.maxLength() + ")");
            }

            if ("enum".equals(field.type())
                    && (field.values() == null || field.values().isEmpty())) {
                throw new PluginValidationException(type,
                    "spec.fields." + fieldName + ": enum type requires non-empty 'values' list");
            }

            if ("list".equals(field.type()) && field.itemType() == null) {
                throw new PluginValidationException(type,
                    "spec.fields." + fieldName + ": list type requires 'item-type'");
            }

            if ("map".equals(field.type()) && field.valueType() == null) {
                throw new PluginValidationException(type,
                    "spec.fields." + fieldName + ": map type requires 'value-type'");
            }
        }
    }

    public static void validateSteps(String type, String section, List<ResolvedStep> steps,
                                      PluginSpecSchema spec, Set<String> knownPrimitives) {
        Set<String> resultBindings = new HashSet<>();

        for (int i = 0; i < steps.size(); i++) {
            ResolvedStep step = steps.get(i);

            if (step instanceof ResolvedStep.PluginStep ps) {
                if (!knownPrimitives.contains(ps.name())) {
                    String suggestion = suggestSimilar(ps.name(), knownPrimitives);
                    throw new PluginValidationException(type,
                        section + " step " + i + ": unknown primitive '"
                            + ps.name() + "'"
                            + (suggestion != null ? " — did you mean '" + suggestion + "'?" : ""));
                }

                Object resultName = step.decorators().get("result");
                if (resultName instanceof String r) {
                    resultBindings.add(r);
                }

                validateInterpolationRefs(type, section + " step " + i,
                    ps.params(), spec, resultBindings);

                Object when = step.decorators().get("when");
                if (when instanceof String w) {
                    validateInterpolationRefsInString(type, section + " step " + i + " when",
                        w, spec, resultBindings);
                }
            }
        }
    }

    static void validateInterpolationRefs(String type, String location,
                                           Map<String, Object> params,
                                           PluginSpecSchema spec,
                                           Set<String> resultBindings) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            validateInterpolationRefsInValue(type, location + "." + entry.getKey(),
                entry.getValue(), spec, resultBindings);
        }
    }

    @SuppressWarnings("unchecked")
    static void validateInterpolationRefsInValue(String type, String location,
                                                  Object value, PluginSpecSchema spec,
                                                  Set<String> resultBindings) {
        if (value instanceof String s) {
            validateInterpolationRefsInString(type, location, s, spec, resultBindings);
        } else if (value instanceof Map<?, ?> m) {
            validateInterpolationRefs(type, location, (Map<String, Object>) m,
                spec, resultBindings);
        } else if (value instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                validateInterpolationRefsInValue(type, location + "[" + i + "]",
                    l.get(i), spec, resultBindings);
            }
        }
    }

    static void validateInterpolationRefsInString(String type, String location,
                                                   String template, PluginSpecSchema spec,
                                                   Set<String> resultBindings) {
        Matcher m = INTERPOLATION_REF.matcher(template);
        while (m.find()) {
            String ref = m.group(1);
            int dot = ref.indexOf('.');
            if (dot < 0) continue;

            String prefix = ref.substring(0, dot);
            String remainder = ref.substring(dot + 1);

            if (!KNOWN_PREFIXES.contains(prefix)) {
                throw new PluginValidationException(type,
                    location + ": unknown prefix '" + prefix + "' in ${" + ref + "}");
            }

            if ("spec".equals(prefix)) {
                String fieldName = remainder.contains(".")
                    ? remainder.substring(0, remainder.indexOf('.'))
                    : remainder;
                if (!spec.fields().containsKey(fieldName)) {
                    String suggestion = suggestSimilar(fieldName, spec.fields().keySet());
                    throw new PluginValidationException(type,
                        location + ": unknown spec field '" + fieldName
                            + "' in ${" + ref + "}"
                            + (suggestion != null
                                ? " — did you mean '" + suggestion + "'?" : ""));
                }
            }

            if ("result".equals(prefix)) {
                String resultName = remainder.contains(".")
                    ? remainder.substring(0, remainder.indexOf('.'))
                    : remainder;
                if (!resultBindings.contains(resultName)) {
                    throw new PluginValidationException(type,
                        location + ": result binding '" + resultName
                            + "' referenced in ${" + ref
                            + "} but no prior step binds this name");
                }
            }
        }
    }

    public static void validateActualStateHasCompareState(String type,
                                                           List<ResolvedStep> steps) {
        long count = steps.stream()
                          .filter(s -> s instanceof ResolvedStep.PluginStep ps
                                       && "compare-state".equals(ps.name()))
                          .count();
        if (count != 1) {
            throw new PluginValidationException(type,
                "actual-state must contain exactly one 'compare-state' step, found " + count);
        }
    }

    public static void validateActualStateNoApprovalGate(String type,
                                                          List<ResolvedStep> steps) {
        if (steps.stream().anyMatch(s -> s instanceof ResolvedStep.PluginStep ps
                                         && "approval-gate".equals(ps.name()))) {
            throw new PluginValidationException(type,
                "actual-state must not contain 'approval-gate' steps");
        }
    }

    public static String suggestSimilar(String input, Set<String> candidates) {
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int dist = levenshtein(input.toLowerCase(), candidate.toLowerCase());
            if (dist <= 2 && dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    public static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
