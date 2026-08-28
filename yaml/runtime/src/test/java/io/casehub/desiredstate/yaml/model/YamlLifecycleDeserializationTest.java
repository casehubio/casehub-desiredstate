package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlLifecycleDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Test
    void deserialize_lifecycleWithPhases() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: lifecycle-test
                lifecycle:
                  phases:
                    - id: infrastructure
                      completionCondition: allPresent
                      nodes:
                        database:
                          type: db
                          spec:
                            engine: postgres
                    - id: application
                      completionCondition: allPresent
                      nodes:
                        api-server:
                          type: app
                          dependsOn: [database]
                          spec:
                            image: "api:latest"
                    - id: observability
                      completionCondition: never
                      nodes:
                        monitor:
                          type: monitor
                          dependsOn: [api-server]
                          spec:
                            target: api-server
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.lifecycle()).isNotNull();
        assertThat(graph.lifecycle().phases()).hasSize(3);
        assertThat(graph.nodes()).isEmpty();

        YamlPhase infra = graph.lifecycle().phases().get(0);
        assertThat(infra.id()).isEqualTo("infrastructure");
        assertThat(infra.completionCondition()).isEqualTo("allPresent");
        assertThat(infra.nodes()).hasSize(1);
        assertThat(infra.nodes()).containsKey("database");

        YamlPhase obs = graph.lifecycle().phases().get(2);
        assertThat(obs.completionCondition()).isEqualTo("never");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deserialize_beanCompletionCondition() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: bean-test
                lifecycle:
                  phases:
                    - id: custom
                      completionCondition:
                        bean: myCustomCondition
                      nodes:
                        node1:
                          type: app
                          spec: {}
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        YamlPhase phase = graph.lifecycle().phases().get(0);
        assertThat(phase.completionCondition()).isInstanceOf(Map.class);
        Map<String, Object> condition = (Map<String, Object>) phase.completionCondition();
        assertThat(condition).containsEntry("bean", "myCustomCondition");
    }

    @Test
    void deserialize_noLifecycle_defaultsToNull() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: no-lifecycle
                nodes:
                  app:
                    type: app
                    spec: {}
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.lifecycle()).isNull();
    }
}
