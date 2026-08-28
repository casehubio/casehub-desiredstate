package io.casehub.desiredstate.yaml.resolver;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariableResolverTest {

    @Test
    void resolvesFromInlineMap() {
        var resolver = new VariableResolver(Map.of("batch", "500"), null, null);
        assertThat(resolver.resolveString("${var.batch}", "test-node"))
                .isEqualTo("500");
    }

    @Test
    void passesNonVariableStringsThrough() {
        var resolver = new VariableResolver(Map.of(), null, null);
        assertThat(resolver.resolve("plain-string"))
                .isEqualTo("plain-string");
    }

    @Test
    void resolvesNestedMapValues() {
        var resolver = new VariableResolver(Map.of("uri", "s3://data"), null, null);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("destination", "${var.uri}");
        input.put("count", 42);

        @SuppressWarnings("unchecked")
        Map<String, Object> resolved = (Map<String, Object>) resolver.resolveMap(input, "node");

        assertThat(resolved).containsEntry("destination", "s3://data");
        assertThat(resolved).containsEntry("count", 42);
    }

    @Test
    void resolvesListValues() {
        var resolver = new VariableResolver(Map.of("field", "email"), null, null);
        @SuppressWarnings("unchecked")
        List<Object> resolved = (List<Object>) resolver.resolveList(
                List.of("name", "${var.field}"), "node");

        assertThat(resolved).containsExactly("name", "email");
    }

    @Test
    void throwsOnUnresolvedVariable() {
        var resolver = new VariableResolver(Map.of("batch_size", "100"), null, null);

        // Bare name now throws with prefix guidance
        assertThatThrownBy(() -> resolver.resolveString("${bacth_size}", "csv-ingest"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("${var.bacth_size}");

        // Correct prefix but typo still throws as unresolved
        assertThatThrownBy(() -> resolver.resolveString("${var.bacth_size}", "csv-ingest"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("bacth_size")
                .hasMessageContaining("csv-ingest");
    }

    @Test
    void embeddedVariableInLargerString() {
        var resolver = new VariableResolver(Map.of("bucket", "prod"), null, null);
        assertThat(resolver.resolveString("s3://${var.bucket}/data", "node"))
                .isEqualTo("s3://prod/data");
    }

    @Test
    void nonStringValuesPassThrough() {
        var resolver = new VariableResolver(Map.of(), null, null);
        assertThat(resolver.resolve(42)).isEqualTo(42);
        assertThat(resolver.resolve(true)).isEqualTo(true);
        assertThat(resolver.resolve(3.14)).isEqualTo(3.14);
    }

    @Test
    void multipleVariablesInOneString() {
        var resolver = new VariableResolver(
                Map.of("proto", "s3", "bucket", "data"), null, null);
        assertThat(resolver.resolveString("${var.proto}://${var.bucket}/path", "node"))
                .isEqualTo("s3://data/path");
    }

    @Test
    void resolveString_withVarPrefix_resolvesFromInlineVariables() {
        var resolver = new VariableResolver(Map.of("source_uri", "s3://data/test.csv"), null, null);
        assertThat(resolver.resolveString("${var.source_uri}", "test-node"))
                .isEqualTo("s3://data/test.csv");
    }

    @Test
    void resolveString_bareName_throwsWithGuidance() {
        var resolver = new VariableResolver(Map.of("source_uri", "s3://data/test.csv"), null, null);
        assertThatThrownBy(() -> resolver.resolveString("${source_uri}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("${var.source_uri}");
    }

    @Test
    void resolveString_matchPrefix_throwsWithGuidance() {
        var resolver = new VariableResolver(Map.of(), null, null);
        assertThatThrownBy(() -> resolver.resolveString("${match.sink.id}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("rule evaluation time");
    }

    @Test
    void resolveString_faultPrefix_throwsWithGuidance() {
        var resolver = new VariableResolver(Map.of(), null, null);
        assertThatThrownBy(() -> resolver.resolveString("${fault.nodeId}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("fault");
    }

    @Test
    void resolveString_eachPrefix_throwsWithGuidance() {
        var resolver = new VariableResolver(Map.of(), null, null);
        assertThatThrownBy(() -> resolver.resolveString("${each.region}", "test-node"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("forEach");
    }


    // --- Template mode: resolves ${var.*}, passes through ${match.*} ---

    @Test
    void resolveTemplateString_resolvesVarPrefix_passesMatchThrough() {
        var resolver = new VariableResolver(Map.of("region", "us-east"), null, null);
        String result = resolver.resolveTemplateString(
                "monitor-${match.sink.id}-${var.region}", "test-rule");
        assertThat(result).isEqualTo("monitor-${match.sink.id}-us-east");
    }

    @Test
    void resolveTemplateString_noVarReferences_passesAllThrough() {
        var resolver = new VariableResolver(Map.of(), null, null);
        String result = resolver.resolveTemplateString(
                "${match.sink.id}", "test-rule");
        assertThat(result).isEqualTo("${match.sink.id}");
    }

    @Test
    void resolveTemplateString_onlyVar_resolvesCompletely() {
        var    resolver = new VariableResolver(Map.of("name", "test"), null, null);
        String result   = resolver.resolveTemplateString("${var.name}", "test-rule");
        assertThat(result).isEqualTo("test");
    }

    @Test
    void resolveTemplateMap_resolvesVarInNestedSpec() {
        var resolver = new VariableResolver(Map.of("region", "us-east"), null, null);
        Map<String, Object> input = Map.of(
                "target", "${match.sink.id}",
                "region", "${var.region}");
        Map<String, Object> result = resolver.resolveTemplateMap(input, "test-rule");
        assertThat(result.get("target")).isEqualTo("${match.sink.id}");
        assertThat(result.get("region")).isEqualTo("us-east");
    }

}
