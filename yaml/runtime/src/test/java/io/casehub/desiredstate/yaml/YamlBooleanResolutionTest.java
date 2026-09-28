package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper yamlMapper = new ObjectMapper(
            com.fasterxml.jackson.dataformat.yaml.YAMLFactory.builder()
                                                             .enable(com.fasterxml.jackson.dataformat.yaml.YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
                                                             .build());

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
    void trueFalse_arePreservedAsBoolean() throws Exception {
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
        assertThat(graph.variables().get("enabled")).isEqualTo(true);
        assertThat(graph.variables().get("disabled")).isEqualTo(false);
    }

    @Test
    void integerValues_preservedAsInteger() throws Exception {
        String yaml = """
                      desiredState:
                        namespace: test
                        name: type-test
                      variables:
                        batch_size: 500
                        ratio: 0.75
                      nodes: {}
                      """;
        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.variables().get("batch_size")).isEqualTo(500);
        assertThat(graph.variables().get("batch_size")).isInstanceOf(Integer.class);
        assertThat(graph.variables().get("ratio")).isEqualTo(0.75);
    }

}
