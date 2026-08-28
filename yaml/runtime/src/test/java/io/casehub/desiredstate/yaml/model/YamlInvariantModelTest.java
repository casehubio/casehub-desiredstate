package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.casehub.desiredstate.annotations.runtime.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YamlInvariantModelTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void sinkUpstreamInvariant_deserializesAllFields() throws Exception {
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                invariants:
                  every-sink-has-upstream:
                    match:
                      sink: { type: sink }
                    directDep:
                      upstream: { type: transformer, of: sink, direction: DEPENDENCIES }
                    message: "Sink ${match.sink.id} has no upstream transformer"
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);

        assertThat(graph.invariants()).hasSize(1);
        assertThat(graph.invariants()).containsKey("every-sink-has-upstream");

        YamlInvariant inv = graph.invariants().get("every-sink-has-upstream");

        assertThat(inv.match()).hasSize(1);
        assertThat(inv.match().get("sink").type()).isEqualTo("sink");

        assertThat(inv.directDep()).hasSize(1);
        YamlPattern upstream = inv.directDep().get("upstream");
        assertThat(upstream.type()).isEqualTo("transformer");
        assertThat(upstream.of()).isEqualTo("sink");
        assertThat(upstream.direction()).isEqualTo(Direction.DEPENDENCIES);

        assertThat(inv.message()).isEqualTo("Sink ${match.sink.id} has no upstream transformer");
    }

    @Test
    void notExistsInvariant_deserializes() throws Exception {
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                invariants:
                  no-orphan-monitors:
                    match:
                      monitor: { type: monitor }
                    notExists:
                      target: { type: "*", of: monitor, direction: DEPENDENCIES }
                    message: "Monitor ${match.monitor.id} has no target dependency"
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        YamlInvariant inv = graph.invariants().get("no-orphan-monitors");

        assertThat(inv.match()).hasSize(1);
        assertThat(inv.notExists()).hasSize(1);
        assertThat(inv.notExists().get("target").type()).isEqualTo("*");
        assertThat(inv.notExists().get("target").of()).isEqualTo("monitor");
    }

    @Test
    void reachesInvariant_deserializes() throws Exception {
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                invariants:
                  gold-reaches-source:
                    match:
                      gold: { type: sink }
                    reaches:
                      source: { type: source, of: gold, direction: DEPENDENCIES }
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        YamlInvariant inv = graph.invariants().get("gold-reaches-source");

        assertThat(inv.reaches()).hasSize(1);
        assertThat(inv.reaches().get("source").type()).isEqualTo("source");
        assertThat(inv.reaches().get("source").of()).isEqualTo("gold");
    }

    @Test
    void directionDefaults_toDependencies() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: defaults
                variables: {}
                nodes: {}
                invariants:
                  test-inv:
                    match:
                      node: { type: sink }
                    directDep:
                      dep: { type: source, of: node }
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        YamlInvariant inv = graph.invariants().get("test-inv");

        assertThat(inv.directDep().get("dep").direction()).isEqualTo(Direction.DEPENDENCIES);
    }

    @Test
    void noInvariants_defaultsToEmptyMap() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: simple
                variables: {}
                nodes: {}
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.invariants()).isEmpty();
    }

    @Test
    void multipleInvariants_allParsed() throws Exception {
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                invariants:
                  inv-a:
                    match:
                      sink: { type: sink }
                    directDep:
                      dep: { type: source, of: sink }
                  inv-b:
                    match:
                      tx: { type: transformer }
                    reaches:
                      src: { type: source, of: tx }
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        assertThat(graph.invariants()).hasSize(2);
        assertThat(graph.invariants()).containsKeys("inv-a", "inv-b");
    }

    @Test
    void graphScopeField_deserializes() throws Exception {
        String yaml = """
                desiredState:
                  namespace: pipeline
                  name: medallion
                variables: {}
                nodes: {}
                invariants:
                  scoped-inv:
                    graph:
                      - "pipeline:*"
                      - "!pipeline:debug"
                    match:
                      node: { type: sink }
                    directDep:
                      dep: { type: source, of: node }
                """;

        YamlGraph graph = yamlMapper.readValue(yaml, YamlGraph.class);
        YamlInvariant inv = graph.invariants().get("scoped-inv");

        assertThat(inv.graph()).containsExactly("pipeline:*", "!pipeline:debug");
    }
}
