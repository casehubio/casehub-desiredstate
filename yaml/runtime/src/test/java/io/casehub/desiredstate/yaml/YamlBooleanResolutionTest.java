package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies YAML boolean resolution follows YAML 1.2 Core Schema:
 * only true/false are boolean literals. yes/no/on/off remain strings.
 *
 * Why this matters: operators write variables like monitoring_enabled: yes
 * and forEach values like [yes, no, maybe]. YAML 1.1 auto-coerces yes→true,
 * corrupting these values silently. YAML 1.2 Core Schema prevents this.
 */
class YamlBooleanResolutionTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void yesValue_remainsString_notCoercedToBoolean() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: bool-test
                variables:
                  monitoring_enabled: yes
                  debug: "true"
                nodes: {}
                """;
        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.variables().get("monitoring_enabled"))
                .as("'yes' should remain a string, not be coerced to boolean 'true'")
                .isEqualTo("yes");
    }

    @Test
    void noValue_remainsString_notCoercedToBoolean() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: bool-test
                variables:
                  feature_flag: no
                nodes: {}
                """;
        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.variables().get("feature_flag"))
                .as("'no' should remain a string, not be coerced to boolean 'false'")
                .isEqualTo("no");
    }

    @Test
    void onOffValues_remainStrings() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: bool-test
                variables:
                  switch_a: "on"
                  switch_b: "off"
                nodes: {}
                """;
        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.variables().get("switch_a")).isEqualTo("on");
        assertThat(graph.variables().get("switch_b")).isEqualTo("off");
    }

    @Test
    void trueFalse_areValidBooleanStrings() throws Exception {
        // true/false are YAML 1.2 booleans — but Map<String,String> stores them as strings
        String yaml = """
                desiredState:
                  namespace: test
                  name: bool-test
                variables:
                  enabled: true
                  disabled: false
                nodes: {}
                """;
        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        // Jackson deserializes YAML booleans to String when target type is String
        assertThat(graph.variables().get("enabled")).isEqualTo("true");
        assertThat(graph.variables().get("disabled")).isEqualTo("false");
    }
}
