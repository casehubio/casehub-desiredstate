package io.casehub.desiredstate.plugin.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.plugin.model.PluginFieldDef;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.plugin.model.PluginStepDef;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import io.quarkus.deployment.annotations.BuildProducer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YamlPluginProcessor {

    private static final String PLUGIN_PATH = "META-INF/desiredstate/plugins/";
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    private static final Set<String> BUILT_IN_PRIMITIVES = Set.of(
        "rest-call", "graphql-call", "json-extract", "compare-state", "assert", "approval-gate");

    private static final Set<String> SUPPORTED_FIELD_TYPES = Set.of(
        "string", "integer", "number", "boolean", "enum", "list", "map");

    private static final Pattern INTERPOLATION_REF = Pattern.compile("\\$\\{([^}]+)}");

    private static final Set<String> KNOWN_PREFIXES = Set.of(
        "spec", "auth", "result", "param", "var", "fault");

    @BuildStep
    void discoverAndValidatePlugins(
            CombinedIndexBuildItem combinedIndex,
            BuildProducer<PluginBuildItem> pluginItems) throws IOException {

        Map<String, String> typeRegistry = scanNodeTypes(combinedIndex.getIndex());
        Set<String> seenPluginTypes = new HashSet<>();

        List<NamedPlugin> discovered = discoverPlugins();
        for (NamedPlugin np : discovered) {
            String type = np.model.header().type();

            if (!seenPluginTypes.add(type)) {
                throw new PluginValidationException(type,
                    "Duplicate plugin type — another plugin YAML already declares this type");
            }

            validatePlugin(np.model, typeRegistry, BUILT_IN_PRIMITIVES);
            pluginItems.produce(new PluginBuildItem(np.fileName, np.model));
        }
    }

    static Map<String, String> scanNodeTypes(IndexView index) {
        java.util.HashMap<String, String> registry = new java.util.HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            String typeId = ann.value().asString();
            String className = ann.target().asClass().name().toString();
            String existing = registry.put(typeId, className);
            if (existing != null) {
                throw new RuntimeException("Duplicate @NodeTypeId(\"" + typeId
                    + "\"): " + existing + " and " + className);
            }
        }
        return registry;
    }

    static void validatePlugin(PluginModel plugin, Map<String, String> typeRegistry,
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

    static void validateSpecSchema(String type, PluginSpecSchema spec) {
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

    static void validateSteps(String type, String section, List<PluginStepDef> steps,
                              PluginSpecSchema spec, Set<String> knownPrimitives) {
        Set<String> resultBindings = new HashSet<>();

        for (int i = 0; i < steps.size(); i++) {
            PluginStepDef step = steps.get(i);

            if (!knownPrimitives.contains(step.primitiveName())) {
                String suggestion = suggestSimilar(step.primitiveName(), knownPrimitives);
                throw new PluginValidationException(type,
                    section + " step " + i + ": unknown primitive '"
                        + step.primitiveName() + "'"
                        + (suggestion != null ? " — did you mean '" + suggestion + "'?" : ""));
            }

            if (step.resultName() != null) {
                resultBindings.add(step.resultName());
            }

            validateInterpolationRefs(type, section + " step " + i,
                step.parameters(), spec, resultBindings);

            if (step.when() != null) {
                validateInterpolationRefsInString(type, section + " step " + i + " when",
                    step.when(), spec, resultBindings);
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

    static void validateActualStateHasCompareState(String type,
                                                   List<PluginStepDef> steps) {
        long count = steps.stream()
            .filter(s -> "compare-state".equals(s.primitiveName()))
            .count();
        if (count != 1) {
            throw new PluginValidationException(type,
                "actual-state must contain exactly one 'compare-state' step, found " + count);
        }
    }

    static void validateActualStateNoApprovalGate(String type,
                                                  List<PluginStepDef> steps) {
        if (steps.stream().anyMatch(s -> "approval-gate".equals(s.primitiveName()))) {
            throw new PluginValidationException(type,
                "actual-state must not contain 'approval-gate' steps");
        }
    }

    static String suggestSimilar(String input, Set<String> candidates) {
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

    static int levenshtein(String a, String b) {
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

    // --- discovery ---

    private List<NamedPlugin> discoverPlugins() throws IOException {
        List<NamedPlugin> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> urls = cl.getResources(PLUGIN_PATH);

        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                discoverFromDirectory(new File(url.getPath()), results, seen);
            } else if ("jar".equals(protocol)) {
                discoverFromJar(url, results, seen);
            }
        }
        return results;
    }

    private void discoverFromDirectory(File dir, List<NamedPlugin> results,
                                       Set<String> seen) throws IOException {
        File[] files = dir.listFiles((d, name) ->
            name.endsWith(".yaml") || name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            if (!seen.add(file.getName())) continue;
            try (InputStream is = new FileInputStream(file)) {
                PluginModel model = PluginParser.parse(is);
                results.add(new NamedPlugin(file.getName(), model));
            }
        }
    }

    private void discoverFromJar(URL url, List<NamedPlugin> results,
                                  Set<String> seen) throws IOException {
        String jarPath = url.getPath();
        int bangIdx = jarPath.indexOf('!');
        if (bangIdx < 0) return;

        String jarFile = jarPath.substring(jarPath.startsWith("file:") ? 5 : 0, bangIdx);
        try (JarInputStream jis = new JarInputStream(new FileInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith(PLUGIN_PATH) && !name.equals(PLUGIN_PATH)
                        && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
                    String fileName = name.substring(PLUGIN_PATH.length());
                    if (!seen.add(fileName)) continue;
                    PluginModel model = PluginParser.parse(jis);
                    results.add(new NamedPlugin(fileName, model));
                }
            }
        }
    }

    record NamedPlugin(String fileName, PluginModel model) {}
}
