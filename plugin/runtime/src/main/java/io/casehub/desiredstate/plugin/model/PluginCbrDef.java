package io.casehub.desiredstate.plugin.model;

import java.util.List;
import java.util.Map;

public record PluginCbrDef(
    List<Feature> features,
    Map<String, String> outcomeSignals
) {
    public record Feature(
        String name,
        String source,
        String similarity,
        String transform
    ) {}
}
