package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlForEachDeserializationTest {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Test
    @SuppressWarnings("unchecked")
    void deserialize_inlineForEach() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: foreach-test
                nodes:
                  regional-source:
                    type: data-source
                    forEach:
                      as: region
                      in: ["us-east", "eu-west", "ap-south"]
                    spec:
                      name: "customers-${each.region}"
                      uri: "s3://data/${each.region}/customers.csv"
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.nodes()).hasSize(1);

        Object forEach = graph.nodes().get("regional-source").forEach();
        assertThat(forEach).isInstanceOf(Map.class);
        Map<String, Object> forEachMap = (Map<String, Object>) forEach;
        assertThat(forEachMap.get("as")).isEqualTo("region");
        List<String> inList = (List<String>) forEachMap.get("in");
        assertThat(inList).containsExactly("us-east", "eu-west", "ap-south");
    }

    @Test
    void deserialize_namedGroupReference() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: named-group-test
                iterations:
                  regional:
                    as: region
                    in: ["us-east", "eu-west"]
                nodes:
                  regional-source:
                    type: data-source
                    forEach: regional
                    spec:
                      uri: "s3://${each.region}/data.csv"
                  regional-ingest:
                    type: ingestion
                    forEach: regional
                    dependsOn: [regional-source]
                    spec:
                      region: "${each.region}"
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.iterations()).hasSize(1);
        assertThat(graph.iterations().get("regional").as()).isEqualTo("region");
        assertThat(graph.iterations().get("regional").inAsList()).containsExactly("us-east", "eu-west");

        assertThat(graph.nodes().get("regional-source").forEach()).isEqualTo("regional");
        assertThat(graph.nodes().get("regional-ingest").forEach()).isEqualTo("regional");
    }

    @Test
    void deserialize_variableSourcedValues() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: var-sourced
                variables:
                  regions: '["us-east", "eu-west"]'
                iterations:
                  regional:
                    as: region
                    in: "${var.regions}"
                nodes:
                  regional-source:
                    type: data-source
                    forEach: regional
                    spec:
                      uri: "s3://${each.region}/data.csv"
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        YamlIterationGroup group = graph.iterations().get("regional");
        assertThat(group.in()).isEqualTo("${var.regions}");
    }

    @Test
    void deserialize_noForEach_defaultsToNull() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: no-foreach
                nodes:
                  app:
                    type: app
                    spec: {}
                """;
        YamlGraph graph = mapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.nodes().get("app").forEach()).isNull();
        assertThat(graph.iterations()).isEmpty();
    }
}
