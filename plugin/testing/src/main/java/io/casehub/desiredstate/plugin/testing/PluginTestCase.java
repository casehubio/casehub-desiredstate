package io.casehub.desiredstate.plugin.testing;

import java.util.List;
import java.util.Map;

public record PluginTestCase(
        String name,
        Map<String, Object> spec,
        List<Map<String, Object>> expectations,
        String action,
        Map<String, String> assertions,
        FaultInjection faultInjection
) {
    public record FaultInjection(String action, int failCount, String error) {}
}
