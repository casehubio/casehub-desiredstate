package io.casehub.desiredstate.plugin.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.jackson.YamlMappers;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PluginTestYamlParser {

    private static final ObjectMapper YAML = YamlMappers.create();

    private PluginTestYamlParser() {}

    public static PluginTestSuite parse(InputStream yaml) throws IOException {
        JsonNode root = YAML.readTree(yaml);

        String pluginType = root.path("plugin").asText();
        String infrastructureType = root.path("infrastructure").asText();

        PluginTestSuite.Setup setup = parseSetup(root.get("setup"));

        List<PluginTestCase> testCases = new ArrayList<>();
        JsonNode testsNode = root.get("tests");
        if (testsNode != null && testsNode.isArray()) {
            for (JsonNode testNode : testsNode) {
                testCases.add(parseTestCase(testNode));
            }
        }

        return new PluginTestSuite(pluginType, infrastructureType, setup, List.copyOf(testCases));
    }

    private static PluginTestSuite.Setup parseSetup(JsonNode setupNode) {
        if (setupNode == null) return PluginTestSuite.Setup.EMPTY;

        List<Map<String, Object>> stubs = new ArrayList<>();
        JsonNode stubsNode = setupNode.get("stubs");
        if (stubsNode != null && stubsNode.isArray()) {
            for (JsonNode stub : stubsNode) {
                stubs.add(toMap(stub));
            }
        }

        Map<String, Object> variables = Map.of();
        JsonNode varsNode = setupNode.get("variables");
        if (varsNode != null && varsNode.isObject()) {
            variables = toMap(varsNode);
        }

        return new PluginTestSuite.Setup(List.copyOf(stubs), variables);
    }

    private static PluginTestCase parseTestCase(JsonNode node) {
        String name = node.path("name").asText();
        String action = node.path("action").asText();

        Map<String, Object> spec = toMap(node.get("spec"));

        List<Map<String, Object>> expectations = new ArrayList<>();
        JsonNode expNode = node.get("expectations");
        if (expNode != null && expNode.isArray()) {
            for (JsonNode e : expNode) {
                expectations.add(toMap(e));
            }
        }

        Map<String, String> assertions = new LinkedHashMap<>();
        JsonNode assertNode = node.get("assert");
        if (assertNode != null && assertNode.isObject()) {
            assertNode.fields().forEachRemaining(e ->
                assertions.put(e.getKey(), e.getValue().asText()));
        }

        PluginTestCase.FaultInjection faultInjection = null;
        JsonNode fiNode = node.get("fault-injection");
        if (fiNode != null) {
            faultInjection = new PluginTestCase.FaultInjection(
                fiNode.path("action").asText(),
                fiNode.path("fail-count").asInt(1),
                fiNode.path("error").asText("Injected failure")
            );
        }

        return new PluginTestCase(name, spec, List.copyOf(expectations),
            action, Map.copyOf(assertions), faultInjection);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        return YAML.convertValue(node, Map.class);
    }
}
