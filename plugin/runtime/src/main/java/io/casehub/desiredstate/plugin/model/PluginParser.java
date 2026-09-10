package io.casehub.desiredstate.plugin.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PluginParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final Set<String> STEP_DIRECTIVES =
        Set.of("result", "when", "on-error", "max-retries", "backoff");

    public static PluginModel parse(InputStream yaml) throws IOException {
        JsonNode root = YAML_MAPPER.readTree(yaml);

        requireSection(root, "plugin");
        requireSection(root, "spec");
        requireSection(root, "actual-state");
        requireSection(root, "provisioner");
        requireSection(root, "cbr");
        requireSection(root, "ras");

        return new PluginModel(
            parseHeader(root.get("plugin")),
            parseSpecSchema(root.get("spec")),
            parseSteps(root.path("actual-state").path("steps")),
            parseProvisioner(root.get("provisioner")),
            parseFaultPolicies(root.path("fault-policy")),
            parseCbr(root.get("cbr")),
            parseRas(root.get("ras"))
        );
    }

    private static PluginHeader parseHeader(JsonNode node) {
        String type = requireText(node, "type", "plugin.type");
        int version = node.path("version").asInt(1);
        String resyncInterval = textOrNull(node, "resyncInterval");

        Map<String, PluginAuthStanza> auth = new LinkedHashMap<>();
        JsonNode authNode = node.get("auth");
        if (authNode != null && authNode.isObject()) {
            authNode.fields().forEachRemaining(e -> {
                String credentialRef = requireText(e.getValue(), "credentialRef",
                    "plugin.auth." + e.getKey() + ".credentialRef");
                auth.put(e.getKey(), new PluginAuthStanza(credentialRef));
            });
        }
        return new PluginHeader(type, version, resyncInterval, Map.copyOf(auth));
    }

    private static PluginSpecSchema parseSpecSchema(JsonNode node) {
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode == null || !fieldsNode.isObject()) {
            throw new PluginParseException("spec.fields is required");
        }
        Map<String, PluginFieldDef> fields = new LinkedHashMap<>();
        fieldsNode.fields().forEachRemaining(e ->
            fields.put(e.getKey(), parseFieldDef(e.getValue())));
        return new PluginSpecSchema(Map.copyOf(fields));
    }

    private static PluginFieldDef parseFieldDef(JsonNode node) {
        return new PluginFieldDef(
            requireText(node, "type", "field.type"),
            node.path("required").asBoolean(false),
            toJavaValueOrNull(node.get("default")),
            textOrNull(node, "pattern"),
            intOrNull(node, "minLength"),
            intOrNull(node, "maxLength"),
            numberOrNull(node, "min"),
            numberOrNull(node, "max"),
            textListOrNull(node, "values"),
            textOrNull(node, "item-type"),
            textOrNull(node, "value-type"),
            intOrNull(node, "minItems"),
            intOrNull(node, "maxItems")
        );
    }

    private static List<PluginStepDef> parseSteps(JsonNode stepsNode) {
        if (stepsNode.isMissingNode() || !stepsNode.isArray()) {
            return List.of();
        }
        List<PluginStepDef> steps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = stepNode.fields();
            if (!fields.hasNext()) {
                continue;
            }
            Map.Entry<String, JsonNode> entry = fields.next();
            String primitiveName = entry.getKey();
            JsonNode paramsNode = entry.getValue();

            String resultName = textOrNull(paramsNode, "result");
            String when = textOrNull(paramsNode, "when");
            String onError = textOrNull(paramsNode, "on-error");
            int maxRetries = paramsNode.path("max-retries").asInt(3);
            String backoff = textOrNull(paramsNode, "backoff");

            Map<String, Object> parameters = new LinkedHashMap<>();
            paramsNode.fields().forEachRemaining(e -> {
                if (!STEP_DIRECTIVES.contains(e.getKey())) {
                    parameters.put(e.getKey(), toJavaValue(e.getValue()));
                }
            });

            steps.add(new PluginStepDef(
                primitiveName, Map.copyOf(parameters),
                resultName, when, onError, maxRetries, backoff));
        }
        return List.copyOf(steps);
    }

    private static PluginProvisionerDef parseProvisioner(JsonNode node) {
        return new PluginProvisionerDef(
            parseSteps(node.path("provision").path("steps")),
            parseSteps(node.path("deprovision").path("steps"))
        );
    }

    private static List<PluginFaultPolicyDef> parseFaultPolicies(JsonNode node) {
        if (node.isMissingNode() || !node.isArray()) {
            return List.of();
        }
        List<PluginFaultPolicyDef> policies = new ArrayList<>();
        for (JsonNode policyNode : node) {
            policies.add(parseFaultPolicy(policyNode));
        }
        return List.copyOf(policies);
    }

    private static PluginFaultPolicyDef parseFaultPolicy(JsonNode node) {
        List<PluginFaultPolicyDef.TierDef> tiers = new ArrayList<>();
        JsonNode tiersNode = node.get("tiers");
        if (tiersNode != null && tiersNode.isArray()) {
            for (JsonNode tierNode : tiersNode) {
                JsonNode reviewNode = tierNode.get("reviewNode");
                PluginFaultPolicyDef.ReviewNodeDef reviewNodeDef = null;
                if (reviewNode != null) {
                    reviewNodeDef = new PluginFaultPolicyDef.ReviewNodeDef(
                        textOrNull(reviewNode, "type"),
                        textOrNull(reviewNode, "humanGating"),
                        toMapOrNull(reviewNode.get("spec"))
                    );
                }
                tiers.add(new PluginFaultPolicyDef.TierDef(
                    tierNode.path("threshold").asInt(),
                    reviewNodeDef
                ));
            }
        }
        return new PluginFaultPolicyDef(
            textList(node, "faultTypes"),
            textList(node, "nodeTypes"),
            textList(node, "ignoreTypes"),
            textOrNull(node, "namespace"),
            List.copyOf(tiers)
        );
    }

    private static PluginCbrDef parseCbr(JsonNode node) {
        List<PluginCbrDef.Feature> features = new ArrayList<>();
        JsonNode featuresNode = node.get("features");
        if (featuresNode != null && featuresNode.isArray()) {
            for (JsonNode f : featuresNode) {
                features.add(new PluginCbrDef.Feature(
                    textOrNull(f, "name"),
                    textOrNull(f, "source"),
                    textOrNull(f, "similarity"),
                    textOrNull(f, "transform")
                ));
            }
        }
        Map<String, String> outcomeSignals = new LinkedHashMap<>();
        JsonNode signalsNode = node.get("outcome-signals");
        if (signalsNode != null && signalsNode.isObject()) {
            signalsNode.fields().forEachRemaining(e ->
                outcomeSignals.put(e.getKey(), e.getValue().asText()));
        }
        return new PluginCbrDef(List.copyOf(features), Map.copyOf(outcomeSignals));
    }

    private static PluginRasDef parseRas(JsonNode node) {
        List<PluginRasDef.Situation> situations = new ArrayList<>();
        JsonNode sitNode = node.get("situations");
        if (sitNode != null && sitNode.isArray()) {
            for (JsonNode s : sitNode) {
                @SuppressWarnings("unchecked")
                Map<String, Object> chainMode = s.has("chain-mode")
                    ? (Map<String, Object>) toJavaValue(s.get("chain-mode"))
                    : Map.of();
                situations.add(new PluginRasDef.Situation(
                    textOrNull(s, "name"),
                    textList(s, "events"),
                    textOrNull(s, "correlation-window"),
                    chainMode,
                    textOrNull(s, "trigger"),
                    textOrNull(s, "trigger-mode"),
                    textOrNull(s, "correlation-key")
                ));
            }
        }
        return new PluginRasDef(List.copyOf(situations));
    }

    // --- helpers ---

    private static void requireSection(JsonNode root, String section) {
        if (!root.has(section)) {
            throw new PluginParseException("Required section '" + section + "' is missing");
        }
    }

    private static String requireText(JsonNode parent, String field, String path) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw new PluginParseException("Required field '" + path + "' is missing or not a string");
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static Integer intOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static Number numberOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isNumber()) {
            return null;
        }
        return node.numberValue();
    }

    private static List<String> textList(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static List<String> textListOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return List.copyOf(result);
    }

    private static Object toJavaValue(JsonNode node) {
        return YAML_MAPPER.convertValue(node, Object.class);
    }

    private static Object toJavaValueOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return toJavaValue(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMapOrNull(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return (Map<String, Object>) toJavaValue(node);
    }
}
