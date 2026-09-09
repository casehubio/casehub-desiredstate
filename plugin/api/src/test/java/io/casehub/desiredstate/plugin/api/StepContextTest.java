package io.casehub.desiredstate.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepContextTest {

    @Test
    void resolveSpecField() {
        var ctx = StepContext.builder()
            .spec(Map.of("namespace", "default", "replicas", 3))
            .build();

        assertThat(ctx.resolve("spec.namespace")).isEqualTo("default");
        assertThat(ctx.resolve("spec.replicas")).isEqualTo(3);
    }

    @Test
    void resolveNestedSpecField() {
        var ctx = StepContext.builder()
            .spec(Map.of("metadata", Map.of("name", "app")))
            .build();

        assertThat(ctx.resolve("spec.metadata.name")).isEqualTo("app");
    }

    @Test
    void resolveAuthCredential() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addAuth("k8s", Map.of("endpoint", "api.k8s.io", "token", "secret"))
            .build();

        assertThat(ctx.resolve("auth.k8s.endpoint")).isEqualTo("api.k8s.io");
        assertThat(ctx.resolve("auth.k8s.token")).isEqualTo("secret");
    }

    @Test
    void resolveStepResult() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("response", StepResult.of(Map.of(
                "status", 200,
                "body", Map.of("status", Map.of("replicas", 3)))))
            .build();

        assertThat(ctx.resolve("result.response.status")).isEqualTo(200);
        assertThat(ctx.resolve("result.response.body.status.replicas")).isEqualTo(3);
    }

    @Test
    void resolveParam() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .params(Map.of("method", "GET", "path", "/api/v1"))
            .build();

        assertThat(ctx.resolve("param.method")).isEqualTo("GET");
    }

    @Test
    void unknownPrefixThrows() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThatThrownBy(() -> ctx.resolve("unknown.field"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown prefix");
    }

    @Test
    void missingSpecFieldReturnsNull() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThat(ctx.resolve("spec.nonexistent")).isNull();
    }

    @Test
    void missingResultReturnsNull() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThat(ctx.resolve("result.missing.field")).isNull();
    }

    @Test
    void addResultAccumulatesContext() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        ctx.addResult("step1", StepResult.of(Map.of("value", "a")));
        ctx.addResult("step2", StepResult.of(Map.of("value", "b")));

        assertThat(ctx.resolve("result.step1.value")).isEqualTo("a");
        assertThat(ctx.resolve("result.step2.value")).isEqualTo("b");
    }

    @Test
    void stepResultDeepPathTraversal() {
        var result = StepResult.of(Map.of(
            "body", Map.of(
                "status", Map.of(
                    "availableReplicas", 3,
                    "conditions", Map.of("type", "Available")))));

        assertThat(result.get("body.status.availableReplicas")).isEqualTo(3);
        assertThat(result.get("body.status.conditions.type")).isEqualTo("Available");
        assertThat(result.get("body.missing.path")).isNull();
    }
}
