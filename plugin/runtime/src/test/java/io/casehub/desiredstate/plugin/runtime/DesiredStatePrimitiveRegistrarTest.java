package io.casehub.desiredstate.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.CompositePluginRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStatePrimitiveRegistrarTest {

    private CompositePluginRegistry registry;
    private StringWriter writer;
    private DesiredStatePrimitiveRegistrar registrar;

    @BeforeEach
    void setUp() {
        registry = new CompositePluginRegistry();
        writer = new StringWriter();
        var condEval = new ConditionEvaluator(
                DesiredStatePrimitiveRegistrarTest::evaluateExpression);
        registrar = new DesiredStatePrimitiveRegistrar(condEval, new ObjectMapper(), writer);
    }

    @Test
    void registersCompareStateAsRealPrimitive() {
        registrar.registerAll(registry);

        assertThat(registry.resolve("compare-state")).isPresent();

        var action = registry.resolve("compare-state").get().action();
        var result = action.execute(
                Map.of("present-when", "true"),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(result.output()).containsEntry("nodeStatus", "PRESENT");
    }

    @Test
    void compareStateReturnsAbsentStatus() {
        registrar.registerAll(registry);

        var action = registry.resolve("compare-state").get().action();
        var result = action.execute(
                Map.of("absent-when", "true"),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("nodeStatus", "ABSENT");
    }

    @Test
    void compareStateDriftedBeforePresent() {
        registrar.registerAll(registry);

        var action = registry.resolve("compare-state").get().action();
        var result = action.execute(
                Map.of("drifted-when", "true", "present-when", "true"),
                new MapServiceRegistry());

        assertThat(result.output()).containsEntry("nodeStatus", "DRIFTED");
    }

    @Test
    void allBuiltInPrimitivesAreRegistered() {
        registrar.registerAll(registry);

        for (String name : PluginValidator.BUILT_IN_PRIMITIVES) {
            assertThat(registry.resolve(name))
                    .as("primitive '%s' should be registered", name)
                    .isPresent();
        }
    }

    @Test
    void writerFallbackPrintsStepAndSucceeds() {
        registrar.registerAll(registry);

        var def = registry.resolve("rest-call").get();
        assertThat(def.portability()).isEqualTo(Portability.TS);

        var result = def.action().execute(
                Map.of("url", "https://api.example.com", "method", "POST"),
                new MapServiceRegistry());

        assertThat(result).isInstanceOf(Result.Success.class);
        assertThat(writer.toString()).contains("rest-call");
        assertThat(writer.toString()).contains("https://api.example.com");
    }

    @Test
    void registersJsonExtractAsRealPrimitive() {
        registrar.registerAll(registry);

        var def = registry.resolve("json-extract").get();
        assertThat(def.portability()).isEqualTo(Portability.JAVA);

        var result = def.action().execute(Map.of(
                "input", "{\"name\": \"myapp\"}",
                "fields", Map.of("n", "name")),
                new MapServiceRegistry());
        assertThat(result.output()).containsEntry("n", "myapp");
    }

    @Test
    void registersGraphqlCallAsRealPrimitive() {
        registrar.registerAll(registry);

        var def = registry.resolve("graphql-call").get();
        assertThat(def.portability()).isEqualTo(Portability.JAVA);
    }

    @Test
    void registersApprovalGateAsRealPrimitive() {
        registrar.registerAll(registry);

        var def = registry.resolve("approval-gate").get();
        assertThat(def.portability()).isEqualTo(Portability.JAVA);

        var result = def.action().execute(
                Map.of("reason", "deploy"), new MapServiceRegistry());
        assertThat(result.output()).containsEntry("status", "pending");
    }

    @Test
    void writerFallbackDoesNotOverrideRealRegistration() {
        var realAction = registry.resolve("compare-state");
        assertThat(realAction).isEmpty();

        registrar.registerAll(registry);

        var def = registry.resolve("compare-state").get();
        assertThat(def.portability()).isEqualTo(Portability.JAVA);
    }

    @Test
    void gapFillDoesNotOverridePlatformRegistrations() {
        registry.register(io.casehub.yaml.plugin.api.Definition.of("rest-call")
                .description("Platform rest-call")
                .portability(Portability.JAVA)
                .execute((params, services) -> Result.of(Map.of("status", 200)))
                .build());

        registrar.registerAll(registry);

        var def = registry.resolve("rest-call").get();
        assertThat(def.portability()).isEqualTo(Portability.JAVA);
        assertThat(def.description()).isEqualTo("Platform rest-call");
    }

    private static boolean evaluateExpression(String expr) {
        String trimmed = expr.trim();
        if (trimmed.contains("!=")) {
            String[] parts = trimmed.split("!=", 2);
            return !parts[0].trim().equals(parts[1].trim());
        }
        if (trimmed.contains("==")) {
            String[] parts = trimmed.split("==", 2);
            return parts[0].trim().equals(parts[1].trim());
        }
        return Boolean.parseBoolean(trimmed);
    }
}
