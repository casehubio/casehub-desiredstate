package io.casehub.desiredstate.plugin.api;

import java.util.Map;

public record PluginSpecSchema(Map<String, PluginFieldDef> fields) {}
