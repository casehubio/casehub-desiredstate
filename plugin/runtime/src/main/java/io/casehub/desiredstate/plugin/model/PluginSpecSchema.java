package io.casehub.desiredstate.plugin.model;

import java.util.Map;

public record PluginSpecSchema(Map<String, PluginFieldDef> fields) {}
