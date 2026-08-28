package io.casehub.desiredstate.yaml.deployment;

import io.casehub.desiredstate.yaml.model.YamlImport;
import io.casehub.desiredstate.yaml.model.YamlModule;
import io.casehub.desiredstate.yaml.model.YamlModuleParameter;
import io.casehub.desiredstate.yaml.model.YamlNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlModuleValidationTest {

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "monitor", "com.example.MonitorSpec",
            "alerter", "com.example.AlerterSpec",
            "sink", "com.example.SinkSpec");

    private final YamlModule monitoringModule = new YamlModule("monitoring",
            Map.of("watched_node_id", new YamlModuleParameter("string", true, null),
                    "alert_email", new YamlModuleParameter("string", false, "ops@example.com")),
            Map.of("monitor", new YamlNode("monitor", Map.of(), List.of(), null, null, null, null, null)),
            Map.of(), Map.of());

    @Test
    void validate_unknownModule_throwsBuildError() {
        var imports = List.of(new YamlImport("nonexistent", "alias", null, Map.of()));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateImports(
                imports, Map.of(), TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("nonexistent");
    }

    @Test
    void validate_missingRequiredParameter_throwsBuildError() {
        var imports = List.of(new YamlImport("monitoring", "mon", null, Map.of()));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateImports(
                imports, Map.of("monitoring", monitoringModule), TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("watched_node_id")
                .hasMessageContaining("required");
    }

    @Test
    void validate_duplicateAlias_throwsBuildError() {
        var imports = List.of(
                new YamlImport("monitoring", "mon", null,
                        Map.of("watched_node_id", "sink-1")),
                new YamlImport("monitoring", "mon", null,
                        Map.of("watched_node_id", "sink-2")));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateImports(
                imports, Map.of("monitoring", monitoringModule), TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("mon")
                .hasMessageContaining("duplicate");
    }

    @Test
    void validate_aliasContainsDot_throwsBuildError() {
        var imports = List.of(new YamlImport("monitoring", "pipe.monitor", null,
                Map.of("watched_node_id", "sink-1")));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateImports(
                imports, Map.of("monitoring", monitoringModule), TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("pipe.monitor")
                .hasMessageContaining(".");
    }

    @Test
    void validate_validImport_passes() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink",
                        "alert_email", "ops@example.com")));
        assertThatCode(() -> YamlDesiredStateProcessor.validateImports(
                imports, Map.of("monitoring", monitoringModule), TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_defaultParameterOmitted_passes() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink")));
        assertThatCode(() -> YamlDesiredStateProcessor.validateImports(
                imports, Map.of("monitoring", monitoringModule), TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }
}
