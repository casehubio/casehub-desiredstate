package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.runtime.StepExecutionException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssertPrimitiveTest {

    private final AssertPrimitive primitive = new AssertPrimitive();

    @Test
    void passesWhenTrue() {
        var params = StepParameters.of(Map.of("condition", "200 in [200, 201]"));
        var ctx = StepContext.builder().spec(Map.of()).build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("passed")).isEqualTo(true);
    }

    @Test
    void failsWithMessage() {
        var params = StepParameters.of(Map.of(
            "condition", "404 in [200, 201]",
            "message", "Provision failed: HTTP 404"));
        var ctx = StepContext.builder().spec(Map.of()).build();
        assertThatThrownBy(() -> primitive.execute(params, ctx))
            .isInstanceOf(StepExecutionException.class)
            .hasMessageContaining("Provision failed: HTTP 404");
    }

    @Test
    void failsWithDefaultMessage() {
        var params = StepParameters.of(Map.of("condition", "1 > 2"));
        var ctx = StepContext.builder().spec(Map.of()).build();
        assertThatThrownBy(() -> primitive.execute(params, ctx))
            .isInstanceOf(StepExecutionException.class)
            .hasMessageContaining("Assertion failed");
    }

    @Test
    void evaluatesConditionWithInterpolation() {
        var params = StepParameters.of(Map.of(
            "condition", "${result.r.status} == 200"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", io.casehub.desiredstate.plugin.api.StepResult.of(
                Map.of("status", 200)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("passed")).isEqualTo(true);
    }

    @Test
    void interpolatesMessage() {
        var params = StepParameters.of(Map.of(
            "condition", "${result.r.status} == 200",
            "message", "Failed: HTTP ${result.r.status}"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", io.casehub.desiredstate.plugin.api.StepResult.of(
                Map.of("status", 404)))
            .build();
        assertThatThrownBy(() -> primitive.execute(params, ctx))
            .isInstanceOf(StepExecutionException.class)
            .hasMessageContaining("Failed: HTTP 404");
    }

    @Test
    void requiresConditionParameter() {
        var params = StepParameters.of(Map.of());
        var ctx = StepContext.builder().spec(Map.of()).build();
        assertThatThrownBy(() -> primitive.execute(params, ctx))
            .isInstanceOf(StepExecutionException.class)
            .hasMessageContaining("condition");
    }
}
