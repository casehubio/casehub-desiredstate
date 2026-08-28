package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.yaml.model.YamlImport;
import io.casehub.desiredstate.yaml.model.YamlModule;
import io.casehub.desiredstate.yaml.model.YamlModuleParameter;
import io.casehub.desiredstate.yaml.model.YamlNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleExpanderTest {

    private final YamlModule monitoringModule = new YamlModule("monitoring",
            Map.of("watched_node_id", new YamlModuleParameter("string", true, null),
                    "alert_email", new YamlModuleParameter("string", false, "ops@example.com")),
            Map.of("monitor", new YamlNode("monitor",
                            Map.of("target", "${var.watched_node_id}"),
                            List.of("${var.watched_node_id}"), null, null, null),
                    "alerter", new YamlNode("alerter",
                            Map.of("email", "${var.alert_email}"),
                            List.of("monitor"), null, null, null)),
            Map.of(), Map.of());

    @Test
    void expand_aliasPrefix_nodeIds() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink",
                        "alert_email", "ops@example.com")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        assertThat(result.expandedNodes()).containsKey("pipe-monitor.monitor");
        assertThat(result.expandedNodes()).containsKey("pipe-monitor.alerter");
    }

    @Test
    void expand_internalDependencies_aliased() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        YamlNode alerter = result.expandedNodes().get("pipe-monitor.alerter");
        assertThat(alerter.dependencyNodeIds()).contains("pipe-monitor.monitor");
    }

    @Test
    void expand_crossBoundaryDependency_preserved() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        YamlNode monitor = result.expandedNodes().get("pipe-monitor.monitor");
        assertThat(monitor.dependencyNodeIds()).contains("${var.watched_node_id}");
    }

    @Test
    void expand_moduleScopes_parameterValues() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink",
                        "alert_email", "custom@example.com")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        assertThat(result.moduleScopes()).containsKey("pipe-monitor");
        assertThat(result.moduleScopes().get("pipe-monitor"))
                .containsEntry("watched_node_id", "warehouse-sink")
                .containsEntry("alert_email", "custom@example.com");
    }

    @Test
    void expand_defaultParameter_appliedWhenOmitted() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        assertThat(result.moduleScopes().get("pipe-monitor"))
                .containsEntry("alert_email", "ops@example.com");
    }

    @Test
    void expand_conditionalImport_whenFieldPreserved() {
        var imports = List.of(new YamlImport("monitoring", "pipe-monitor",
                "${var.monitoring_enabled}",
                Map.of("watched_node_id", "warehouse-sink")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        YamlNode monitor = result.expandedNodes().get("pipe-monitor.monitor");
        assertThat(monitor.when()).isEqualTo("${var.monitoring_enabled}");
    }

    @Test
    void expand_twoImports_independentInstances() {
        var imports = List.of(
                new YamlImport("monitoring", "pipe-monitor", null,
                        Map.of("watched_node_id", "sink-1")),
                new YamlImport("monitoring", "schema-monitor", null,
                        Map.of("watched_node_id", "sink-2")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule),
                new LinkedHashMap<>());

        assertThat(result.expandedNodes()).hasSize(4);
        assertThat(result.expandedNodes()).containsKey("pipe-monitor.monitor");
        assertThat(result.expandedNodes()).containsKey("schema-monitor.monitor");
    }

    @Test
    void expand_existingNodesPreserved() {
        var existingNodes = new LinkedHashMap<String, YamlNode>();
        existingNodes.put("warehouse-sink", new YamlNode("sink",
                Map.of("destination", "s3://warehouse/"),
                List.of(), null, null, null));

        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink")));

        var result = ModuleExpander.expand(imports,
                Map.of("monitoring", monitoringModule), existingNodes);

        assertThat(result.expandedNodes()).containsKey("warehouse-sink");
        assertThat(result.expandedNodes()).containsKey("pipe-monitor.monitor");
        assertThat(result.expandedNodes()).hasSize(3);
    }
}
