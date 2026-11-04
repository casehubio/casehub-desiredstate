package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.casehub.desiredstate.api.DiscoveredNode;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlGraphSerializer {

    private YamlGraphSerializer() {}

    public static String serialize(String namespace, String name,
                                    List<DiscoveredNode> resources) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> desiredState = new LinkedHashMap<>();
        desiredState.put("namespace", namespace);
        desiredState.put("name", name);
        root.put("desiredState", desiredState);

        Map<String, Object> nodes = new LinkedHashMap<>();
        for (DiscoveredNode resource : resources) {
            String nodeId = sanitizeId(resource.resourceId());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("type", resource.type().value());
            if (!resource.spec().isEmpty()) {
                node.put("spec", new LinkedHashMap<>(resource.spec()));
            }
            nodes.put(nodeId, node);
        }
        root.put("nodes", nodes);

        try {
            YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
            ObjectMapper mapper = new ObjectMapper(yamlFactory);
            mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);
            mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
            StringWriter writer = new StringWriter();
            mapper.writeValue(writer, root);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize YAML", e);
        }
    }

    static String sanitizeId(String resourceId) {
        return resourceId.toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-+", "-")
            .replaceFirst("^-", "")
            .replaceFirst("-$", "");
    }
}
