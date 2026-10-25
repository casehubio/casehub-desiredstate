package io.casehub.desiredstate.plugin.runtime.primitives;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphqlCallPrimitiveTest {

    private final GraphqlCallPrimitive primitive =
            new GraphqlCallPrimitive(new ObjectMapper());

    @Test
    void failsWithoutUrl() {
        var result = primitive.execute(
                Map.of("query", "{ users { id } }"),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void failsWithoutQuery() {
        var result = primitive.execute(
                Map.of("url", "https://api.example.com/graphql"),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void failsWithBlankQuery() {
        var result = primitive.execute(
                Map.of("url", "https://api.example.com/graphql", "query", ""),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void unreachableHostReturnsFailedResult() {
        var result = primitive.execute(
                Map.of("url", "http://192.0.2.1:1/graphql",
                       "query", "{ users { id } }",
                       "timeout", 1),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Failure.class);
    }
}
