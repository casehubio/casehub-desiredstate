package io.casehub.desiredstate.plugin.model;

import java.util.List;

public record PluginFieldDef(
    String type,
    boolean required,
    Object defaultValue,
    String pattern,
    Integer minLength,
    Integer maxLength,
    Number min,
    Number max,
    List<String> values,
    String itemType,
    String valueType,
    Integer minItems,
    Integer maxItems
) {}
