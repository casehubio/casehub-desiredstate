package io.casehub.desiredstate.plugin.model;

import java.util.List;
import java.util.Optional;

public record PluginRequires(
    Optional<String> runtimeVersionRange,
    List<String> primitives,
    List<String> types
) {
    public static final PluginRequires NONE = new PluginRequires(
        Optional.empty(), List.of(), List.of());
}
