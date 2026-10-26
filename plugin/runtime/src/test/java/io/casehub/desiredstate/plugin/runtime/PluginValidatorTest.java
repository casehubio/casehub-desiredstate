package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.PluginFieldDef;
import io.casehub.desiredstate.plugin.model.PluginHeader;
import io.casehub.desiredstate.plugin.model.PluginRequires;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginProvisionerDef;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.yaml.step.catalog.ResolvedStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginValidatorTest {

    @Test
    void validPluginPassesValidation() {
        var plugin = createValidPlugin();
        assertThatCode(() ->
            PluginValidator.validatePlugin(plugin, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .doesNotThrowAnyException();
    }

    @Test
    void unknownFieldTypeThrows() {
        var spec = new PluginSpecSchema(Map.of(
            "data", new PluginFieldDef("complex", false, null, null, null, null,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("bad-type", spec);

        assertThatThrownBy(() ->
            PluginValidator.validatePlugin(plugin, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("unsupported type 'complex'");
    }

    @Test
    void unknownPrimitiveSuggestsSimilar() {
        var step = new ResolvedStep.PluginStep("rest-cll",
            "rest-cll", Map.of(), Map.of());
        var spec = new PluginSpecSchema(Map.of());
        var plugin = createPlugin("typo", spec,
            List.of(step), List.of(), List.of(step));

        assertThatThrownBy(() ->
            PluginValidator.validatePlugin(plugin, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("rest-call");
    }

    @Test
    void actualStateMissingCompareStateThrows() {
        var step = new ResolvedStep.PluginStep("assert",
            "assert",
            Map.of("condition", "true"), Map.of());
        var spec = new PluginSpecSchema(Map.of(
            "name", new PluginFieldDef("string", true, null, null, null, null,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("no-compare", spec,
            List.of(step), List.of(step), List.of(step));

        assertThatThrownBy(() ->
            PluginValidator.validatePlugin(plugin, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("compare-state");
    }

    @Test
    void typeConflictWithJavaNodeTypeThrows() {
        var plugin = createValidPlugin();
        var typeRegistry = Map.of("test-resource", "com.example.TestResource");

        assertThatThrownBy(() ->
            PluginValidator.validatePlugin(plugin, typeRegistry, PluginValidator.BUILT_IN_PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("Type conflict");
    }

    @Test
    void inconsistentMinMaxThrows() {
        var spec = new PluginSpecSchema(Map.of(
            "count", new PluginFieldDef("integer", false, null, null, null, null,
                10, 5, null, null, null, null, null)));
        var plugin = createPlugin("bad-range", spec);

        assertThatThrownBy(() ->
            PluginValidator.validatePlugin(plugin, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("min")
            .hasMessageContaining("max");
    }

    @Test
    void actualStateWithApprovalGateThrows() {
        var approvalStep = new ResolvedStep.PluginStep("approval-gate",
            "approval-gate", Map.of(), Map.of());
        var compareStep = new ResolvedStep.PluginStep("compare-state",
            "compare-state",
            Map.of("present-when", "true"), Map.of());
        var assertStep = new ResolvedStep.PluginStep("assert",
            "assert",
            Map.of("condition", "true"), Map.of());
        var spec = new PluginSpecSchema(Map.of(
            "name", new PluginFieldDef("string", true, null, null, null, null,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("bad-actual", spec,
            List.of(compareStep, approvalStep), List.of(assertStep), List.of(assertStep));

        assertThatThrownBy(() ->
            PluginValidator.validatePlugin(plugin, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("approval-gate");
    }

    private static PluginModel createValidPlugin() {
        return createPlugin("test-resource",
            new PluginSpecSchema(Map.of(
                "name", new PluginFieldDef("string", true, null, null, null, null,
                    null, null, null, null, null, null, null))));
    }

    private static PluginModel createPlugin(String type, PluginSpecSchema spec) {
        var compareStep = new ResolvedStep.PluginStep("compare-state",
            "compare-state",
            Map.of("present-when", "true"), Map.of());
        var assertStep = new ResolvedStep.PluginStep("assert",
            "assert",
            Map.of("condition", "true"), Map.of());
        return createPlugin(type, spec,
            List.of(compareStep), List.of(assertStep), List.of(assertStep));
    }

    private static PluginModel createPlugin(String type, PluginSpecSchema spec,
                                            List<ResolvedStep> actualState,
                                            List<ResolvedStep> provision,
                                            List<ResolvedStep> deprovision) {
        return new PluginModel(
                new PluginHeader(type, "1.0.0", "30s", Map.of(), null, PluginRequires.NONE),
                spec, actualState,
                new PluginProvisionerDef(provision, deprovision, List.of(), List.of()),
                List.of(), null, null);
    }
}
