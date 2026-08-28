package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlModuleDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Test
    void deserialize_moduleFile() throws Exception {
        String yaml = """
                module:
                  name: monitoring
                  parameters:
                    watched_node_id:
                      type: string
                      required: true
                    alert_email:
                      type: string
                      default: "ops@example.com"
                nodes:
                  monitor:
                    type: monitor
                    dependsOn: ["${var.watched_node_id}"]
                    spec:
                      target: "${var.watched_node_id}"
                  alerter:
                    type: alerter
                    dependsOn: [monitor]
                    spec:
                      email: "${var.alert_email}"
                """;
        YamlModuleFile file = mapper.readValue(yaml, YamlModuleFile.class);
        YamlModule module = file.toModule();

        assertThat(module.name()).isEqualTo("monitoring");
        assertThat(module.parameters()).hasSize(2);
        assertThat(module.parameters().get("watched_node_id").required()).isTrue();
        assertThat(module.parameters().get("alert_email").defaultValue())
                .isEqualTo("ops@example.com");
        assertThat(module.nodes()).hasSize(2);
        assertThat(module.nodes()).containsKey("monitor");
        assertThat(module.nodes()).containsKey("alerter");
    }

    @Test
    void deserialize_graphWithImports() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: import-test
                nodes:
                  warehouse-sink:
                    type: sink
                    spec:
                      destination: s3://warehouse/
                imports:
                  - module: monitoring
                    as: pipe-monitor
                    parameters:
                      watched_node_id: warehouse-sink
                      alert_email: "pipeline-ops@example.com"
                  - module: monitoring
                    as: schema-monitor
                    when: "${var.monitoring_enabled}"
                    parameters:
                      watched_node_id: customer-schema
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.imports()).hasSize(2);
        assertThat(graph.imports().get(0).module()).isEqualTo("monitoring");
        assertThat(graph.imports().get(0).as()).isEqualTo("pipe-monitor");
        assertThat(graph.imports().get(0).parameters())
                .containsEntry("watched_node_id", "warehouse-sink");
        assertThat(graph.imports().get(1).when()).isEqualTo("${var.monitoring_enabled}");
    }

    @Test
    void deserialize_moduleWithInvariants() throws Exception {
        String yaml = """
                module:
                  name: monitored
                  parameters:
                    watched_node_id:
                      type: string
                      required: true
                nodes:
                  monitor:
                    type: monitor
                    dependsOn: ["${var.watched_node_id}"]
                    spec:
                      target: "${var.watched_node_id}"
                invariants:
                  monitor-must-have-dep:
                    match:
                      mon: { type: monitor }
                    directDep:
                      target: { type: "*", of: mon, direction: DEPENDENCIES }
                """;
        YamlModuleFile file = mapper.readValue(yaml, YamlModuleFile.class);
        YamlModule module = file.toModule();
        assertThat(module.invariants()).hasSize(1);
        assertThat(module.invariants()).containsKey("monitor-must-have-dep");
    }

    @Test
    void deserialize_noImports_defaultsToEmpty() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: no-imports
                nodes:
                  app:
                    type: app
                    spec: {}
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.imports()).isEmpty();
    }
}
