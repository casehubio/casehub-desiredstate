package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.CompositePluginRegistry;
import io.casehub.yaml.step.eval.CatalogStepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDiscoveryTest {

    private static PluginModel mockResourcePlugin;

    @BeforeAll
    static void loadPlugin() throws IOException {
        try (var is = PluginDiscoveryTest.class.getResourceAsStream(
                "/META-INF/desiredstate/plugins/mock-resource.yaml")) {
            mockResourcePlugin = PluginParser.parse(is);
        }
    }

    @Test
    void discover_returnsEmptyForEmptyPluginList() {
        List<BeanRegistration> beans = new PluginDiscovery().discover(List.of());
        assertThat(beans).isEmpty();
    }

    @Test
    void discoverAll_returnsEmptyForEmptyPluginList() {
        var condEval = new ConditionEvaluator(Boolean::parseBoolean);
        var evaluator = new StructuralStepEvaluator(condEval);
        var registry = new CompositePluginRegistry();
        new DesiredStatePrimitiveRegistrar(condEval,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StringWriter()).registerAll(registry);
        var runner = new CatalogStepRunner(registry, new MapServiceRegistry());

        List<BeanRegistration> beans = new PluginDiscovery()
                .discoverAll(List.of(), evaluator, runner, ref -> Map.of());
        assertThat(beans).isEmpty();
    }

    @Test
    void discoverAll_producesProvisionerAndAdapterBeans() {
        var condEval = new ConditionEvaluator(expr -> {
            if (expr.contains("==")) {
                String[] parts = expr.split("==", 2);
                return parts[0].trim().equals(parts[1].trim());
            }
            return Boolean.parseBoolean(expr.trim());
        });
        var evaluator = new StructuralStepEvaluator(condEval);
        var registry = new CompositePluginRegistry();
        new DesiredStatePrimitiveRegistrar(condEval,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new StringWriter()).registerAll(registry);
        var runner = new CatalogStepRunner(registry, new MapServiceRegistry());

        List<BeanRegistration> beans = new PluginDiscovery()
                .discoverAll(List.of(mockResourcePlugin), evaluator, runner,
                    ref -> Map.of("token", "test", "endpoint", "test.local"));

        assertThat(beans).extracting(BeanRegistration::type)
                .contains(PluginDescriptor.class, NodeProvisioner.class,
                    ActualStateAdapter.class);

        assertThat(beans).extracting(BeanRegistration::type)
                .filteredOn(t -> t == YamlPluginRasRegistrar.class)
                .hasSize(1);
        assertThat(beans).extracting(BeanRegistration::type)
                .filteredOn(t -> t == CbrPluginMetadata.class)
                .hasSize(1);
    }
}
