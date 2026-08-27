package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.api.HumanGating;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlGraphDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Test
    void deserializesMinimalGraph() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: simple
                nodes:
                  my-node:
                    type: data-source
                    spec:
                      name: test-source
                """;

        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.desiredState().namespace()).isEqualTo("test");
        assertThat(graph.desiredState().name()).isEqualTo("simple");
        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.nodes().get("my-node").type()).isEqualTo("data-source");
        assertThat(graph.nodes().get("my-node").spec()).containsEntry("name", "test-source");
    }

    @Test
    void deserializesVariablesAndDependencies() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: deps
                variables:
                  batch: "500"
                nodes:
                  source:
                    type: data-source
                    spec:
                      name: src
                  ingest:
                    type: ingestion
                    dependsOn: [source]
                    spec:
                      batchSize: ${batch}
                """;

        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.variables()).containsEntry("batch", "500");
        assertThat(graph.nodes().get("ingest").dependsOn()).containsExactly("source");
    }

    @Test
    void deserializesHumanGating() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: gated
                nodes:
                  gated-node:
                    type: transformer
                    humanGating: PROVISION_ONLY
                    spec:
                      name: test
                """;

        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.nodes().get("gated-node").humanGating())
                .isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test
    void defaultsHumanGatingToNone() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: default
                nodes:
                  plain:
                    type: data-source
                    spec:
                      name: test
                """;

        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.nodes().get("plain").humanGating()).isEqualTo(HumanGating.NONE);
    }

    @Test
    void specDefaultsToEmptyMap() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: nospec
                nodes:
                  marker:
                    type: marker
                """;

        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.nodes().get("marker").spec()).isNotNull().isEmpty();
    }
}
