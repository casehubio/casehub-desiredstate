package io.casehub.desiredstate.bridge.ansible;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AnsibleManifestExtensionParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AnsibleManifestExtensionParser() {}

    public static Map<String, AnsibleModuleConfig> parse(InputStream is) {
        try {
            JsonNode root = YAML.readTree(is);
            JsonNode bridge = root.path("bridge");
            JsonNode types = bridge.path("types");

            Map<String, AnsibleModuleConfig> configs = new LinkedHashMap<>();
            if (types.isArray()) {
                for (JsonNode typeDef : types) {
                    String id = typeDef.path("id").asText();
                    JsonNode ansible = typeDef.path("ansible");
                    if (!ansible.isMissingNode()) {
                        configs.put(id, toModuleConfig(ansible));
                    }
                }
            }
            return configs;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse Ansible manifest extension", e);
        }
    }

    private static AnsibleModuleConfig toModuleConfig(JsonNode node) {
        return new AnsibleModuleConfig(
            node.path("collection").asText(null),
            node.path("provision_module").asText(null),
            node.path("deprovision_strategy").asText("state_absent"),
            node.path("deprovision_module").asText(null),
            node.path("info_module").asText(null),
            node.path("info_filter_key").asText(null),
            node.path("result_key").asText(null),
            toStringList(node.path("output_keys")),
            toStringList(node.path("drift_keys"))
        );
    }

    private static List<String> toStringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(item.asText());
        }
        return result;
    }
}
