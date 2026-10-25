package io.casehub.desiredstate.plugin.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.Portability;
import io.casehub.yaml.step.catalog.AptPluginSource;
import io.casehub.yaml.step.catalog.CompositePluginRegistry;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformPrimitiveRegistrationTest {

    @Test
    void platformPrimitivesAutoRegisterFromClasspath() {
        var registry = new CompositePluginRegistry();
        new AptPluginSource(new ObjectMapper()).populate(registry);

        assertThat(registry.resolve("rest-call")).isPresent();
        assertThat(registry.resolve("assert")).isPresent();
        assertThat(registry.resolve("process")).isPresent();
    }

    @Test
    void platformPrimitivesNotOverriddenByWriterFallback() {
        var registry = new CompositePluginRegistry();
        new AptPluginSource(new ObjectMapper()).populate(registry);
        new DesiredStatePrimitiveRegistrar(
                new ConditionEvaluator(Boolean::parseBoolean),
                new ObjectMapper(), new StringWriter())
                .registerAll(registry);

        var restCall = registry.resolve("rest-call").get();
        assertThat(restCall.portability()).isNotEqualTo(Portability.TS);
    }

    @Test
    void allBuiltInPrimitivesResolveAfterFullRegistration() {
        var registry = new CompositePluginRegistry();
        new AptPluginSource(new ObjectMapper()).populate(registry);
        new DesiredStatePrimitiveRegistrar(
                new ConditionEvaluator(Boolean::parseBoolean),
                new ObjectMapper(), new StringWriter())
                .registerAll(registry);

        for (String name : PluginValidator.BUILT_IN_PRIMITIVES) {
            assertThat(registry.resolve(name))
                    .as("primitive '%s' should resolve", name)
                    .isPresent();
        }
    }
}
