package io.casehub.desiredstate.plugin.testing;

import java.util.List;
import java.util.Map;

public record PluginTestSuite(
        String pluginType,
        String infrastructureType,
        Setup setup,
        List<PluginTestCase> testCases
) {
    public record Setup(
            List<Map<String, Object>> stubs,
            Map<String, Object> variables
    ) {
        public static final Setup EMPTY = new Setup(List.of(), Map.of());
    }
}
