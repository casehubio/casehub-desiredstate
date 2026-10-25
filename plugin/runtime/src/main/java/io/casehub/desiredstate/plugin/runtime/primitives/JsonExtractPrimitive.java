package io.casehub.desiredstate.plugin.runtime.primitives;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

public class JsonExtractPrimitive implements Action {

    private final ObjectMapper mapper;

    public JsonExtractPrimitive(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Result execute(Map<String, Object> parameters, ServiceRegistry services) {
        Object inputRaw = parameters.get("input");
        if (inputRaw == null) {
            return Result.failed("json-extract requires 'input' parameter");
        }

        JsonNode root;
        try {
            if (inputRaw instanceof String s) {
                root = mapper.readTree(s);
            } else {
                root = mapper.valueToTree(inputRaw);
            }
        } catch (JsonProcessingException e) {
            return Result.failed("json-extract: invalid JSON input — " + e.getMessage());
        }

        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) parameters.get("fields");
        if (fields == null || fields.isEmpty()) {
            return Result.failed("json-extract requires 'fields' map of name → JSON path");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String name = entry.getKey();
            String path = entry.getValue();
            JsonNode value = resolvePath(root, path);
            Object converted = nodeToValue(value);
            if (converted != null) {
                output.put(name, converted);
            }
        }
        return Result.of(output);
    }

    private static JsonNode resolvePath(JsonNode root, String path) {
        String[] parts = path.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isMissingNode()) return null;
            if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                current = current.get(part);
            }
        }
        return current;
    }

    private static Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble() || node.isFloat()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        return node.toString();
    }
}
