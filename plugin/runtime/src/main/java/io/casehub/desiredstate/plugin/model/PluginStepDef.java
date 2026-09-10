package io.casehub.desiredstate.plugin.model;

import java.util.Map;

public record PluginStepDef(
    String primitiveName,
    Map<String, Object> parameters,
    String resultName,
    String when,
    String onError,
    int maxRetries,
    String backoff
) {}
