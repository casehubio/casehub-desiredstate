package io.casehub.desiredstate.yaml.deployment;

import io.casehub.desiredstate.yaml.model.YamlIterationGroup;
import io.casehub.desiredstate.yaml.model.YamlNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlForEachValidationTest {

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "data-source", "com.example.DataSourceSpec",
            "ingestion", "com.example.IngestionSpec");

    @Test
    void validate_dotInNodeId_throwsBuildError() {
        var nodes = Map.of("my.source", new YamlNode("data-source",
                Map.of(), List.of(), null, null, null, null, null));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, Map.of(), TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining(".")
                .hasMessageContaining("my.source");
    }

    @Test
    void validate_nonForEachDependsOnForEachTemplate_throwsBuildError() {
        var nodes = Map.of(
                "regional-source", new YamlNode("data-source",
                        Map.of(), List.of(), null, null, "regional", null, null),
                "processor", new YamlNode("ingestion",
                        Map.of(), List.of("regional-source"), null, null, null, null, null));
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east", "eu-west")));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, iterations, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("processor")
                .hasMessageContaining("regional-source")
                .hasMessageContaining("forEach");
    }

    @Test
    void validate_crossGroupDependency_throwsBuildError() {
        var nodes = Map.of(
                "source", new YamlNode("data-source",
                        Map.of(), List.of(), null, null, "group-a", null, null),
                "sink", new YamlNode("ingestion",
                        Map.of(), List.of("source"), null, null, "group-b", null, null));
        var iterations = Map.of(
                "group-a", new YamlIterationGroup("region", List.of("us-east")),
                "group-b", new YamlIterationGroup("zone", List.of("z1")));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, iterations, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("group-a")
                .hasMessageContaining("group-b");
    }

    @Test
    void validate_unknownGroupReference_throwsBuildError() {
        var nodes = Map.of("source", new YamlNode("data-source",
                Map.of(), List.of(), null, null, "nonexistent", null, null));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, Map.of(), TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("nonexistent");
    }

    @Test
    void validate_inlineForEachDependsOnNamedGroup_throwsBuildError() {
        Map<String, Object> inlineForEach = Map.of("as", "idx", "in", List.of("1", "2"));
        var nodes = Map.of(
                "named-src", new YamlNode("data-source",
                        Map.of(), List.of(), null, null, "regional", null, null),
                "inline-proc", new YamlNode("ingestion",
                        Map.of(), List.of("named-src"), null, null, inlineForEach, null, null));
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east")));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, iterations, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("inline-proc")
                .hasMessageContaining("named-src");
    }

    @Test
    void validate_dotInForEachValue_throwsBuildError() {
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us.east", "eu-west")));
        var nodes = Map.of("source", new YamlNode("data-source",
                Map.of(), List.of(), null, null, "regional", null, null));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, iterations, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("us.east")
                .hasMessageContaining(".");
    }

    @Test
    void validate_validForEach_passes() {
        var nodes = new java.util.LinkedHashMap<String, YamlNode>();
        nodes.put("regional-source", new YamlNode("data-source",
                Map.of(), List.of(), null, null, "regional", null, null));
        nodes.put("regional-ingest", new YamlNode("ingestion",
                Map.of(), List.of("regional-source"), null, null, "regional", null, null));
        nodes.put("fixed-node", new YamlNode("data-source",
                Map.of(), List.of(), null, null, null, null, null));
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east", "eu-west")));
        assertThatCode(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, iterations, TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_forEachDependsOnFixedNode_passes() {
        var nodes = new java.util.LinkedHashMap<String, YamlNode>();
        nodes.put("fixed-db", new YamlNode("data-source",
                Map.of(), List.of(), null, null, null, null, null));
        nodes.put("regional-source", new YamlNode("ingestion",
                Map.of(), List.of("fixed-db"), null, null, "regional", null, null));
        var iterations = Map.of("regional",
                new YamlIterationGroup("region", List.of("us-east")));
        assertThatCode(() -> YamlDesiredStateProcessor.validateForEach(
                nodes, iterations, TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }
}
