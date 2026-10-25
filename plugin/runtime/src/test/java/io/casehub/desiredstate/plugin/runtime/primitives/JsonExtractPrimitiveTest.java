package io.casehub.desiredstate.plugin.runtime.primitives;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExtractPrimitiveTest {

    private final JsonExtractPrimitive primitive = new JsonExtractPrimitive(new ObjectMapper());

    @Test
    void extractsSimpleFields() {
        var result = primitive.execute(Map.of(
                "input", "{\"name\": \"myapp\", \"port\": 8080}",
                "fields", Map.of("appName", "name", "appPort", "port")),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(result.output()).containsEntry("appName", "myapp");
        assertThat(result.output()).containsEntry("appPort", 8080);
    }

    @Test
    void extractsNestedFields() {
        var result = primitive.execute(Map.of(
                "input", "{\"data\": {\"user\": {\"email\": \"a@b.com\"}}}",
                "fields", Map.of("email", "data.user.email")),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("email", "a@b.com");
    }

    @Test
    void extractsArrayElements() {
        var result = primitive.execute(Map.of(
                "input", "{\"items\": [\"alpha\", \"beta\", \"gamma\"]}",
                "fields", Map.of("second", "items.1")),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("second", "beta");
    }

    @Test
    void missingPathOmittedFromOutput() {
        var result = primitive.execute(Map.of(
                "input", "{\"name\": \"myapp\"}",
                "fields", Map.of("missing", "nonexistent.path")),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(result.output()).doesNotContainKey("missing");
    }

    @Test
    void missingInputFails() {
        var result = primitive.execute(Map.of(
                "fields", Map.of("x", "y")),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void missingFieldsFails() {
        var result = primitive.execute(Map.of(
                "input", "{\"a\": 1}"),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void invalidJsonInputFails() {
        var result = primitive.execute(Map.of(
                "input", "not json {{{",
                "fields", Map.of("x", "y")),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void extractsBooleansAndOmitsNulls() {
        var result = primitive.execute(Map.of(
                "input", "{\"active\": true, \"deleted\": null}",
                "fields", Map.of("isActive", "active", "isDeleted", "deleted")),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("isActive", true);
        assertThat(result.output()).doesNotContainKey("isDeleted");
    }

    @Test
    void acceptsMapInputDirectly() {
        var result = primitive.execute(Map.of(
                "input", Map.of("status", "ok", "count", 42),
                "fields", Map.of("s", "status", "c", "count")),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("s", "ok");
        assertThat(result.output()).containsEntry("c", 42);
    }
}
