package io.casehub.desiredstate.plugin.model;

import java.util.Map;

public record PluginHeader(
    String type,
    int version,
    String resyncInterval,
    Map<String, PluginAuthStanza> auth
) {}
