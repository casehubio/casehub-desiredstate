package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.PluginFieldDef;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class PluginSpecValidator {

    private PluginSpecValidator() {}

    public static void validate(String type, Map<String, Object> fields, PluginSpecSchema schema) {
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, PluginFieldDef> entry : schema.fields().entrySet()) {
            String fieldName = entry.getKey();
            PluginFieldDef def = entry.getValue();
            Object value = fields.get(fieldName);

            if (value == null) {
                if (def.required()) {
                    errors.add(fieldName + ": required field is missing or null");
                }
                continue;
            }

            validateType(fieldName, value, def, errors);
            validateConstraints(fieldName, value, def, errors);
        }

        if (!errors.isEmpty()) {
            throw new PluginValidationException(type,
                "spec validation failed:\n  " + String.join("\n  ", errors));
        }
    }

    private static void validateType(String fieldName, Object value, PluginFieldDef def,
                                      List<String> errors) {
        switch (def.type()) {
            case "string" -> {
                if (!(value instanceof String)) {
                    errors.add(fieldName + ": expected string, got " + value.getClass().getSimpleName());
                }
            }
            case "integer" -> {
                if (!(value instanceof Integer) && !(value instanceof Long)) {
                    errors.add(fieldName + ": expected integer, got " + value.getClass().getSimpleName());
                }
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    errors.add(fieldName + ": expected number, got " + value.getClass().getSimpleName());
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    errors.add(fieldName + ": expected boolean, got " + value.getClass().getSimpleName());
                }
            }
            case "enum" -> {
                if (!(value instanceof String)) {
                    errors.add(fieldName + ": expected string for enum, got " + value.getClass().getSimpleName());
                } else if (def.values() != null && !def.values().contains(value)) {
                    errors.add(fieldName + ": value '" + value + "' not in allowed values " + def.values());
                }
            }
            case "list" -> {
                if (!(value instanceof List)) {
                    errors.add(fieldName + ": expected list, got " + value.getClass().getSimpleName());
                } else if (def.itemType() != null) {
                    validateListItems(fieldName, (List<?>) value, def.itemType(), errors);
                }
            }
            case "map" -> {
                if (!(value instanceof Map)) {
                    errors.add(fieldName + ": expected map, got " + value.getClass().getSimpleName());
                } else if (def.valueType() != null) {
                    validateMapValues(fieldName, (Map<?, ?>) value, def.valueType(), errors);
                }
            }
        }
    }

    private static void validateConstraints(String fieldName, Object value, PluginFieldDef def,
                                             List<String> errors) {
        if (value instanceof String s) {
            if (def.pattern() != null && !Pattern.matches(def.pattern(), s)) {
                errors.add(fieldName + ": value does not match pattern '" + def.pattern() + "'");
            }
            if (def.minLength() != null && s.length() < def.minLength()) {
                errors.add(fieldName + ": length " + s.length() + " below minLength " + def.minLength());
            }
            if (def.maxLength() != null && s.length() > def.maxLength()) {
                errors.add(fieldName + ": length " + s.length() + " exceeds maxLength " + def.maxLength());
            }
        }

        if (value instanceof Number n) {
            if (def.min() != null && n.doubleValue() < def.min().doubleValue()) {
                errors.add(fieldName + ": value " + n + " below min " + def.min());
            }
            if (def.max() != null && n.doubleValue() > def.max().doubleValue()) {
                errors.add(fieldName + ": value " + n + " exceeds max " + def.max());
            }
        }

        if (value instanceof List<?> list) {
            if (def.minItems() != null && list.size() < def.minItems()) {
                errors.add(fieldName + ": size " + list.size() + " below minItems " + def.minItems());
            }
            if (def.maxItems() != null && list.size() > def.maxItems()) {
                errors.add(fieldName + ": size " + list.size() + " exceeds maxItems " + def.maxItems());
            }
        }
    }

    private static void validateListItems(String fieldName, List<?> list, String itemType,
                                           List<String> errors) {
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!matchesScalarType(item, itemType)) {
                errors.add(fieldName + "[" + i + "]: expected " + itemType
                    + ", got " + item.getClass().getSimpleName());
            }
        }
    }

    private static void validateMapValues(String fieldName, Map<?, ?> map, String valueType,
                                           List<String> errors) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!matchesScalarType(entry.getValue(), valueType)) {
                errors.add(fieldName + "." + entry.getKey() + ": expected " + valueType
                    + ", got " + entry.getValue().getClass().getSimpleName());
            }
        }
    }

    private static boolean matchesScalarType(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> true;
        };
    }
}
