package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeTypeDescriptor;
import io.casehub.desiredstate.plugin.api.PluginFieldDef;
import io.casehub.desiredstate.plugin.api.PluginSpecSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SchemaCache {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path cacheDir;

    public SchemaCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public void store(String namespace, String type, String version,
                      List<DiscoveredType> types) {
        Path file = resolvePath(namespace, type, version);
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (var dt : types) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("nodeType", dt.nodeType().value());
                entry.put("fields", serializeFields(dt.specSchema()));
                serialized.add(entry);
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), serialized);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write schema cache: " + file, e);
        }
    }

    public Optional<List<DiscoveredType>> load(String namespace, String type, String version) {
        Path file = resolvePath(namespace, type, version);
        if (!Files.exists(file)) return Optional.empty();
        try {
            List<Map<String, Object>> serialized = JSON.readValue(
                file.toFile(), new TypeReference<>() {});
            List<DiscoveredType> types = new ArrayList<>();
            for (var entry : serialized) {
                String nodeTypeValue = (String) entry.get("nodeType");
                NodeType nodeType = NodeType.of(nodeTypeValue);
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> rawFields =
                    (Map<String, Map<String, Object>>) entry.get("fields");
                PluginSpecSchema specSchema = deserializeFields(rawFields);
                BridgeTypeDescriptor typeDesc = new BridgeTypeDescriptor(
                    nodeType, Map.of(), NodeStatus.DRIFTED, NodeStatus.ABSENT,
                    Map.of(), BridgeErrorCategory.PERMANENT);
                types.add(new DiscoveredType(nodeType, specSchema, typeDesc));
            }
            return Optional.of(types);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path resolvePath(String namespace, String type, String version) {
        return cacheDir.resolve(namespace).resolve(type).resolve(version).resolve("schema.json");
    }

    private Map<String, Object> serializeFields(PluginSpecSchema schema) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : schema.fields().entrySet()) {
            Map<String, Object> fieldMap = new LinkedHashMap<>();
            PluginFieldDef f = entry.getValue();
            fieldMap.put("type", f.type());
            fieldMap.put("required", f.required());
            if (f.sensitive()) fieldMap.put("sensitive", true);
            if (f.deprecated()) fieldMap.put("deprecated", true);
            result.put(entry.getKey(), fieldMap);
        }
        return result;
    }

    private PluginSpecSchema deserializeFields(Map<String, Map<String, Object>> rawFields) {
        if (rawFields == null) return new PluginSpecSchema(Map.of());
        Map<String, PluginFieldDef> fields = new LinkedHashMap<>();
        for (var entry : rawFields.entrySet()) {
            String fieldType = (String) entry.getValue().get("type");
            boolean required = Boolean.TRUE.equals(entry.getValue().get("required"));
            boolean sensitive = Boolean.TRUE.equals(entry.getValue().get("sensitive"));
            boolean deprecated = Boolean.TRUE.equals(entry.getValue().get("deprecated"));
            fields.put(entry.getKey(), new PluginFieldDef(
                fieldType, required, null, null,
                null, null, null, null, null, null, null, null, null,
                sensitive, deprecated));
        }
        return new PluginSpecSchema(fields);
    }
}
