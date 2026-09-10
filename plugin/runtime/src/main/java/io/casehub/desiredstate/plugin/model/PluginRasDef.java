package io.casehub.desiredstate.plugin.model;

import java.util.List;
import java.util.Map;

public record PluginRasDef(List<Situation> situations) {

    public record Situation(
        String name,
        List<String> events,
        String correlationWindow,
        Map<String, Object> chainMode,
        String trigger,
        String triggerMode,
        String correlationKey
    ) {}
}
