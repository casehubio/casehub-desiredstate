package io.casehub.desiredstate.plugin.model;

import java.util.List;
import java.util.Map;

public record PluginFaultPolicyDef(
    List<String> faultTypes,
    List<String> nodeTypes,
    List<String> ignoreTypes,
    String namespace,
    List<TierDef> tiers
) {
    public record TierDef(
        int threshold,
        ReviewNodeDef reviewNode
    ) {}

    public record ReviewNodeDef(
        String type,
        String humanGating,
        Map<String, Object> spec
    ) {}
}
