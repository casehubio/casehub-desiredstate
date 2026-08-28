package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.yaml.model.YamlIterationGroup;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import io.casehub.desiredstate.yaml.resolver.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForEachExpanderTest {

    public record TestSpec(String name, String uri) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("data-source"); }
    }

    private static final Map<String, String> TYPE_MAP = Map.of(
            "data-source", "io.casehub.desiredstate.yaml.ForEachExpanderTest$TestSpec",
            "ingestion", "io.casehub.desiredstate.yaml.ForEachExpanderTest$TestSpec");

    private final NodeSpecRegistry registry = NodeSpecRegistry.of(TYPE_MAP);
    private final ObjectMapper mapper = new ObjectMapper();
    private final VariableResolver resolver = new VariableResolver(Map.of(), null, null);

    @Test
    void inlineForEach_stampsThreeCopies() {
        Map<String, Object> inlineForEach = Map.of("as", "region",
                "in", List.of("us-east", "eu-west", "ap-south"));
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("regional-source", new YamlNode("data-source",
                Map.of("name", "customers-${each.region}",
                       "uri", "s3://${each.region}/data.csv"),
                List.of(), null, null, inlineForEach, null, null));

        var result = ForEachExpander.expand(nodes, Map.of(), resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).hasSize(3);
        assertThat(result.nodes().stream().map(n -> n.id().value()).toList())
                .containsExactlyInAnyOrder("regional-source.us-east",
                        "regional-source.eu-west", "regional-source.ap-south");

        DesiredNode usEast = result.nodes().stream()
                .filter(n -> n.id().value().equals("regional-source.us-east"))
                .findFirst().orElseThrow();
        TestSpec spec = (TestSpec) usEast.spec();
        assertThat(spec.name()).isEqualTo("customers-us-east");
        assertThat(spec.uri()).isEqualTo("s3://us-east/data.csv");
    }

    @Test
    void namedGroup_alignedDependencies() {
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east", "eu-west")));
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("regional-source", new YamlNode("data-source",
                Map.of("name", "${each.region}", "uri", "s3://${each.region}"),
                List.of(), null, null, "regional", null, null));
        nodes.put("regional-ingest", new YamlNode("ingestion",
                Map.of("name", "${each.region}-ingest", "uri", ""),
                List.of("regional-source"), null, null, "regional", null, null));

        var result = ForEachExpander.expand(nodes, iterations, resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).hasSize(4);
        assertThat(result.dependencies()).contains(
                new Dependency(NodeId.of("regional-ingest.us-east"),
                        NodeId.of("regional-source.us-east")));
        assertThat(result.dependencies()).contains(
                new Dependency(NodeId.of("regional-ingest.eu-west"),
                        NodeId.of("regional-source.eu-west")));
        assertThat(result.dependencies()).doesNotContain(
                new Dependency(NodeId.of("regional-ingest.us-east"),
                        NodeId.of("regional-source.eu-west")));
    }

    @Test
    void forEachDependsOnFixedNode_eachCopyDependsOnSame() {
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east", "eu-west")));
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("fixed-db", new YamlNode("data-source",
                Map.of("name", "db", "uri", "jdbc://db"),
                List.of(), null, null, null, null, null));
        nodes.put("regional-source", new YamlNode("data-source",
                Map.of("name", "${each.region}", "uri", ""),
                List.of("fixed-db"), null, null, "regional", null, null));

        var result = ForEachExpander.expand(nodes, iterations, resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).hasSize(3);
        assertThat(result.dependencies()).contains(
                new Dependency(NodeId.of("regional-source.us-east"), NodeId.of("fixed-db")));
        assertThat(result.dependencies()).contains(
                new Dependency(NodeId.of("regional-source.eu-west"), NodeId.of("fixed-db")));
    }

    @Test
    void expansionLimit_exceeded_throws() {
        Map<String, Object> inlineForEach = Map.of("as", "idx",
                "in", java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(String::valueOf).toList());
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("node", new YamlNode("data-source",
                Map.of("name", "${each.idx}", "uri", ""),
                List.of(), null, null, inlineForEach, null, null));

        assertThatThrownBy(() -> ForEachExpander.expand(nodes, Map.of(),
                resolver, registry, mapper, 3))
                .hasMessageContaining("node")
                .hasMessageContaining("5")
                .hasMessageContaining("3");
    }

    @Test
    void variableSourcedValues_jsonArray() {
        var resolver = new VariableResolver(
                Map.of("regions", "[\"us-east\", \"eu-west\"]"), null, null);
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", "${var.regions}"));
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("source", new YamlNode("data-source",
                Map.of("name", "${each.region}", "uri", ""),
                List.of(), null, null, "regional", null, null));

        var result = ForEachExpander.expand(nodes, iterations, resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes().stream().map(n -> n.id().value()).toList())
                .containsExactlyInAnyOrder("source.us-east", "source.eu-west");
    }

    @Test
    void forEachPlusWhen_allCopiesExcluded() {
        var resolver = new VariableResolver(
                Map.of("enable_sources", "false"), null, null);
        Map<String, Object> inlineForEach = Map.of("as", "region",
                "in", List.of("us-east", "eu-west"));
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("source", new YamlNode("data-source",
                Map.of("name", "${each.region}", "uri", ""),
                List.of(), null, "${var.enable_sources}", inlineForEach, null, null));

        var result = ForEachExpander.expand(nodes, Map.of(), resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).isEmpty();
        assertThat(result.excludedNodeIds())
                .containsExactlyInAnyOrder("source.us-east", "source.eu-west");
    }

    @Test
    void zeroValues_noDependents_producesEmpty() {
        Map<String, Object> inlineForEach = Map.of("as", "idx", "in", List.of());
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("empty-template", new YamlNode("data-source",
                Map.of("name", "x", "uri", ""),
                List.of(), null, null, inlineForEach, null, null));

        var result = ForEachExpander.expand(nodes, Map.of(), resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).isEmpty();
    }

    @Test
    void mixedForEachAndFixed_correctNodeCount() {
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east", "eu-west")));
        var nodes = new LinkedHashMap<String, YamlNode>();
        nodes.put("fixed-db", new YamlNode("data-source",
                Map.of("name", "db", "uri", "jdbc://db"),
                List.of(), null, null, null, null, null));
        nodes.put("fixed-schema", new YamlNode("data-source",
                Map.of("name", "schema", "uri", ""),
                List.of(), null, null, null, null, null));
        nodes.put("regional-source", new YamlNode("data-source",
                Map.of("name", "${each.region}", "uri", ""),
                List.of("fixed-db"), null, null, "regional", null, null));

        var result = ForEachExpander.expand(nodes, iterations, resolver,
                registry, mapper, 1000);

        assertThat(result.nodes()).hasSize(4);
    }
}
