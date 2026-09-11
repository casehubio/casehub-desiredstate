package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.step.StepContext;
import io.casehub.yaml.step.StepParameters;
import io.casehub.yaml.step.StepResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompareStatePrimitiveTest {

    private final CompareStatePrimitive primitive = new CompareStatePrimitive();

    @Test
    void mapsToPresent() {
        var params = StepParameters.of(Map.of(
            "present-when", "200 == 200",
            "drifted-when", "3 < 3",
            "absent-when", "200 == 404"));
        var ctx = StepContext.builder().build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("PRESENT");
    }

    @Test
    void mapsToDrifted() {
        var params = StepParameters.of(Map.of(
            "present-when", "200 == 200",
            "drifted-when", "1 < 3",
            "absent-when", "200 == 404"));
        var ctx = StepContext.builder().build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("DRIFTED");
    }

    @Test
    void mapsToAbsent() {
        var params = StepParameters.of(Map.of(
            "present-when", "404 == 200",
            "absent-when", "404 == 404"));
        var ctx = StepContext.builder().build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("ABSENT");
    }

    @Test
    void mapsToUnknownWhenNoConditionMatches() {
        var params = StepParameters.of(Map.of(
            "present-when", "500 == 200"));
        var ctx = StepContext.builder().build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("UNKNOWN");
    }

    @Test
    void absentCheckedBeforeDrifted() {
        var params = StepParameters.of(Map.of(
            "present-when", "404 == 200",
            "drifted-when", "404 == 404",
            "absent-when", "404 == 404"));
        var ctx = StepContext.builder().build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("ABSENT");
    }

    @Test
    void handlesNullConditionValues() {
        var params = StepParameters.of(Map.of(
            "present-when", "null == 200"));
        var ctx = StepContext.builder().build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("UNKNOWN");
    }
}
