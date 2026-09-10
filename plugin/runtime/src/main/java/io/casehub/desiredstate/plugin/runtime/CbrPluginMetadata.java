package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.PluginCbrDef;

import java.util.List;
import java.util.Map;

public record CbrPluginMetadata(
    String pluginType,
    List<PluginCbrDef.Feature> features,
    Map<String, String> outcomeSignals
) {
    public static CbrPluginMetadata from(String pluginType, PluginCbrDef cbr) {
        return new CbrPluginMetadata(pluginType, cbr.features(), cbr.outcomeSignals());
    }
}
