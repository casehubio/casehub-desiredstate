package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.ServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

class CompareStatePrimitiveTest {

    private final CompareStatePrimitive primitive = new CompareStatePrimitive(
            new ConditionEvaluator(null));

    private static final ServiceRegistry SERVICES = new MapServiceRegistry();

    @Test
    void mapsToPresent() {
        var result = primitive.execute(Map.of(
                "present-when", "true",
                "drifted-when", "false",
                "absent-when", "false"), SERVICES);
        assertThat(((Result.Success) result).output().get("nodeStatus")).isEqualTo("PRESENT");
    }

    @Test
    void mapsToDrifted() {
        var result = primitive.execute(Map.of(
                "present-when", "false",
                "drifted-when", "true",
                "absent-when", "false"), SERVICES);
        assertThat(((Result.Success) result).output().get("nodeStatus")).isEqualTo("DRIFTED");
    }

    @Test
    void mapsToAbsent() {
        var result = primitive.execute(Map.of(
                "present-when", "false",
                "absent-when", "true"), SERVICES);
        assertThat(((Result.Success) result).output().get("nodeStatus")).isEqualTo("ABSENT");
    }

    @Test
    void mapsToUnknownWhenNoConditionMatches() {
        var result = primitive.execute(Map.of(
                "present-when", "false"), SERVICES);
        assertThat(((Result.Success) result).output().get("nodeStatus")).isEqualTo("UNKNOWN");
    }

    @Test
    void absentCheckedBeforeDrifted() {
        var result = primitive.execute(Map.of(
                "present-when", "false",
                "drifted-when", "true",
                "absent-when", "true"), SERVICES);
        assertThat(((Result.Success) result).output().get("nodeStatus")).isEqualTo("ABSENT");
    }

    @Test
    void handlesNullConditionValues() {
        var result = primitive.execute(Map.of(
                "present-when", "false"), SERVICES);
        assertThat(((Result.Success) result).output().get("nodeStatus")).isEqualTo("UNKNOWN");
    }
}
