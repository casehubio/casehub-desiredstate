package io.casehub.desiredstate.plugin.model;

import java.util.Map;

public record PluginHeader(
        String type,
        String version,
        String resyncInterval,
        Map<String, PluginAuthStanza> auth,
        String description,
        PluginRequires requires
) {}
