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
        assertThat(resolver.resolveString("${batch}", "test-node"))
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
        input.put("destination", "${uri}");
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
                List.of("name", "${field}"), "node");

        assertThat(resolved).containsExactly("name", "email");
    }

    @Test
    void throwsOnUnresolvedVariable() {
        var resolver = new VariableResolver(Map.of("batch_size", "100"), null, null);

        assertThatThrownBy(() -> resolver.resolveString("${bacth_size}", "csv-ingest"))
                .isInstanceOf(UnresolvedVariableException.class)
                .hasMessageContaining("bacth_size")
                .hasMessageContaining("csv-ingest");
    }

    @Test
    void embeddedVariableInLargerString() {
        var resolver = new VariableResolver(Map.of("bucket", "prod"), null, null);
        assertThat(resolver.resolveString("s3://${bucket}/data", "node"))
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
        assertThat(resolver.resolveString("${proto}://${bucket}/path", "node"))
                .isEqualTo("s3://data/path");
    }
}
