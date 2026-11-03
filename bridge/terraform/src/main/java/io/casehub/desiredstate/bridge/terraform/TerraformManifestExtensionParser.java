package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TerraformManifestExtensionParser {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private TerraformManifestExtensionParser() {}

    public static Map<String, TerraformSuspendConfig> parse(InputStream is) {
        try {
            JsonNode root = YAML.readTree(is);
            JsonNode types = root.path("bridge").path("types");

            Map<String, TerraformSuspendConfig> configs = new LinkedHashMap<>();
            if (types.isArray()) {
                for (JsonNode typeDef : types) {
                    String id = typeDef.path("id").asText();
                    JsonNode terraform = typeDef.path("terraform");
                    if (!terraform.isMissingNode()
                            && !terraform.path("suspend_attribute").isMissingNode()) {
                        configs.put(id, toSuspendConfig(terraform));
                    }
                }
            }
            return configs;
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to parse Terraform manifest extension", e);
        }
    }

    private static TerraformSuspendConfig toSuspendConfig(JsonNode node) {
        return new TerraformSuspendConfig(
            node.path("suspend_attribute").asText(),
            node.path("suspend_value").asText(),
            node.path("resume_value").asText());
    }
}
