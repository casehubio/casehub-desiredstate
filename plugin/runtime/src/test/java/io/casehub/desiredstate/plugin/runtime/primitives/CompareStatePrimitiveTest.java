package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompareStatePrimitiveTest {

    private final CompareStatePrimitive primitive = new CompareStatePrimitive();

    @Test
    void mapsToPresent() {
        var params = StepParameters.of(Map.of(
            "present-when", "${result.r.status} == 200",
            "drifted-when", "${result.r.replicas} < 3",
            "absent-when", "${result.r.status} == 404"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", StepResult.of(Map.of("status", 200, "replicas", 3)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("PRESENT");
    }

    @Test
    void mapsToDrifted() {
        var params = StepParameters.of(Map.of(
            "present-when", "${result.r.status} == 200",
            "drifted-when", "${result.r.replicas} < 3",
            "absent-when", "${result.r.status} == 404"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", StepResult.of(Map.of("status", 200, "replicas", 1)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("DRIFTED");
    }

    @Test
    void mapsToAbsent() {
        var params = StepParameters.of(Map.of(
            "present-when", "${result.r.status} == 200",
            "absent-when", "${result.r.status} == 404"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", StepResult.of(Map.of("status", 404)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("ABSENT");
    }

    @Test
    void mapsToUnknownWhenNoConditionMatches() {
        var params = StepParameters.of(Map.of(
            "present-when", "${result.r.status} == 200"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", StepResult.of(Map.of("status", 500)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("UNKNOWN");
    }

    @Test
    void absentCheckedBeforeDrifted() {
        var params = StepParameters.of(Map.of(
            "present-when", "${result.r.status} == 200",
            "drifted-when", "${result.r.status} == 404",
            "absent-when", "${result.r.status} == 404"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", StepResult.of(Map.of("status", 404)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("ABSENT");
    }

    @Test
    void handlesNullConditionValues() {
        var params = StepParameters.of(Map.of(
            "present-when", "${result.r.missing} == 200"));
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("r", StepResult.of(Map.of("status", 200)))
            .build();
        var result = primitive.execute(params, ctx);
        assertThat(result.get("nodeStatus")).isEqualTo("UNKNOWN");
    }
}
