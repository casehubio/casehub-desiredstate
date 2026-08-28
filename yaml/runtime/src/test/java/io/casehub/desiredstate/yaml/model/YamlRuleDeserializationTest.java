package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.annotations.runtime.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlRuleDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Test
    @SuppressWarnings("unchecked")
    void deserialize_ruleWithAddNodeAndAddDependency() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: rule-test
                nodes: {}
                rules:
                  ensure-monitoring:
                    match:
                      sink: { type: sink }
                    notExists:
                      guard: { type: monitor, of: sink, direction: DEPENDENTS }
                    actions:
                      - addNode:
                          id: "monitor-${match.sink.id}"
                          type: monitor
                          spec:
                            target: "${match.sink.id}"
                      - addDependency:
                          from: "monitor-${match.sink.id}"
                          to: "${match.sink.id}"
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.rules()).hasSize(1);

        YamlRule rule = graph.rules().get("ensure-monitoring");
        assertThat(rule.match()).hasSize(1);
        assertThat(rule.match().get("sink").type()).isEqualTo("sink");
        assertThat(rule.notExists()).hasSize(1);
        assertThat(rule.notExists().get("guard").of()).isEqualTo("sink");
        assertThat(rule.notExists().get("guard").direction()).isEqualTo(Direction.DEPENDENTS);
        assertThat(rule.actions()).hasSize(2);

        Map<String, Object> addNodeAction = rule.actions().get(0);
        assertThat(addNodeAction).containsKey("addNode");
        Map<String, Object> addNodeParams =
                (Map<String, Object>) addNodeAction.get("addNode");
        assertThat(addNodeParams.get("id")).isEqualTo("monitor-${match.sink.id}");
        assertThat(addNodeParams.get("type")).isEqualTo("monitor");
    }

    @Test
    void deserialize_ruleWithGraphScope() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: scope-test
                nodes: {}
                rules:
                  scoped-rule:
                    graph: ["pipeline:*"]
                    match:
                      node: { type: sink }
                    actions:
                      - removeNode:
                          id: "${match.node.id}"
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        YamlRule rule = graph.rules().get("scoped-rule");
        assertThat(rule.graph()).containsExactly("pipeline:*");
    }

    @Test
    void deserialize_emptyRulesDefaultsToEmptyMap() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: no-rules
                nodes: {}
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.rules()).isEmpty();
    }
}
