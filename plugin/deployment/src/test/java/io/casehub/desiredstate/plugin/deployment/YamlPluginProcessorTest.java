package io.casehub.desiredstate.plugin.deployment;

import io.casehub.desiredstate.plugin.model.PluginCbrDef;
import io.casehub.desiredstate.plugin.model.PluginFieldDef;
import io.casehub.desiredstate.plugin.model.PluginHeader;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginProvisionerDef;
import io.casehub.desiredstate.plugin.model.PluginRasDef;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.plugin.model.PluginStepDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlPluginProcessorTest {

    private static final Set<String> PRIMITIVES = Set.of(
        "rest-call", "graphql-call", "json-extract", "compare-state", "assert", "approval-gate");

    @Test
    void validPluginPassesValidation() {
        var plugin = createValidPlugin();
        assertThatCode(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .doesNotThrowAnyException();
    }

    @Test
    void unknownFieldTypeFailsBuild() {
        var spec = new PluginSpecSchema(Map.of(
            "data", new PluginFieldDef("complex", false, null, null, null, null,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("bad-type", spec);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("unsupported type 'complex'");
    }

    @Test
    void inconsistentMinMaxFailsBuild() {
        var spec = new PluginSpecSchema(Map.of(
            "count", new PluginFieldDef("integer", false, null, null, null, null,
                10, 5, null, null, null, null, null)));
        var plugin = createPlugin("bad-range", spec);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("min")
            .hasMessageContaining("max");
    }

    @Test
    void inconsistentMinMaxLengthFailsBuild() {
        var spec = new PluginSpecSchema(Map.of(
            "name", new PluginFieldDef("string", false, null, null, 10, 5,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("bad-length", spec);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("minLength")
            .hasMessageContaining("maxLength");
    }

    @Test
    void enumWithoutValuesFailsBuild() {
        var spec = new PluginSpecSchema(Map.of(
            "mode", new PluginFieldDef("enum", false, null, null, null, null,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("bad-enum", spec);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("enum")
            .hasMessageContaining("values");
    }

    @Test
    void listWithoutItemTypeFailsBuild() {
        var spec = new PluginSpecSchema(Map.of(
            "tags", new PluginFieldDef("list", false, null, null, null, null,
                null, null, null, null, null, null, null)));
        var plugin = createPlugin("bad-list", spec);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("list")
            .hasMessageContaining("item-type");
    }

    @Test
    void unknownPrimitiveFailsBuild() {
        var spec = validSpec();
        var steps = List.of(step("rest-callz", Map.of("method", "GET"), "r"));
        var plugin = createPluginWithSteps("bad-prim", spec, steps);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("rest-callz")
            .hasMessageContaining("did you mean 'rest-call'");
    }

    @Test
    void invalidSpecRefFailsBuild() {
        var spec = new PluginSpecSchema(Map.of(
            "namespace", new PluginFieldDef("string", true, null, null, null, null,
                null, null, null, null, null, null, null)));
        var steps = List.of(
            step("rest-call", Map.of("url", "https://api/${spec.namespce}"), "r"),
            step("compare-state", Map.of("present-when", "true"), null));
        var plugin = createPluginWithSteps("bad-ref", spec, steps);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("namespce")
            .hasMessageContaining("did you mean 'namespace'");
    }

    @Test
    void typeConflictWithJavaFailsBuild() {
        var plugin = createValidPlugin();
        var typeRegistry = Map.of("test-resource", "com.example.TestResource");

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, typeRegistry, PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("Type conflict")
            .hasMessageContaining("@NodeTypeId");
    }

    @Test
    void resultBindingForwardRefFailsBuild() {
        var spec = validSpec();
        var steps = List.of(
            step("assert", Map.of("condition", "${result.missing.status} == 200"), null),
            step("compare-state", Map.of("present-when", "true"), null));
        var plugin = createPluginWithSteps("bad-result", spec, steps);

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("result binding 'missing'");
    }

    @Test
    void resultBindingFromPriorStepPasses() {
        var spec = validSpec();
        var steps = List.of(
            step("rest-call", Map.of("method", "GET", "url", "https://api/test"), "r"),
            step("compare-state", Map.of(
                "present-when", "${result.r.status} == 200",
                "absent-when", "${result.r.status} == 404"), null));
        var plugin = createPluginWithSteps("good-result", spec, steps);

        assertThatCode(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .doesNotThrowAnyException();
    }

    @Test
    void actualStateMustHaveCompareState() {
        var spec = validSpec();
        var actualSteps = List.of(
            step("rest-call", Map.of("method", "GET", "url", "https://api"), "r"));
        var provisionSteps = List.of(step("assert", Map.of("condition", "true"), null));
        var plugin = new PluginModel(
            new PluginHeader("no-compare", 1, null, Map.of()),
            spec, actualSteps,
            new PluginProvisionerDef(provisionSteps, provisionSteps),
            List.of(), emptyCbr(), emptyRas());

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("compare-state")
            .hasMessageContaining("exactly one");
    }

    @Test
    void actualStateMustNotHaveApprovalGate() {
        var spec = validSpec();
        var actualSteps = List.of(
            step("approval-gate", Map.of("when", "true"), null),
            step("compare-state", Map.of("present-when", "true"), null));
        var provisionSteps = List.of(step("assert", Map.of("condition", "true"), null));
        var plugin = new PluginModel(
            new PluginHeader("bad-gate", 1, null, Map.of()),
            spec, actualSteps,
            new PluginProvisionerDef(provisionSteps, provisionSteps),
            List.of(), emptyCbr(), emptyRas());

        assertThatThrownBy(() ->
            YamlPluginProcessor.validatePlugin(plugin, Map.of(), PRIMITIVES))
            .isInstanceOf(PluginValidationException.class)
            .hasMessageContaining("approval-gate");
    }

    @Test
    void levenshteinDistanceIsCorrect() {
        assertThat(YamlPluginProcessor.levenshtein("rest-call", "rest-callz")).isEqualTo(1);
        assertThat(YamlPluginProcessor.levenshtein("assert", "assert")).isEqualTo(0);
        assertThat(YamlPluginProcessor.levenshtein("namespace", "namespce")).isEqualTo(1);
        assertThat(YamlPluginProcessor.levenshtein("abc", "xyz")).isEqualTo(3);
    }

    @Test
    void suggestSimilarFindsCloseMatch() {
        assertThat(YamlPluginProcessor.suggestSimilar("rest-callz",
            Set.of("rest-call", "assert", "compare-state")))
            .isEqualTo("rest-call");
    }

    @Test
    void suggestSimilarReturnsNullForDistantMatch() {
        assertThat(YamlPluginProcessor.suggestSimilar("completely-different",
            Set.of("rest-call", "assert")))
            .isNull();
    }

    // --- helpers ---

    private static PluginModel createValidPlugin() {
        var spec = validSpec();
        var actualSteps = List.of(
            step("rest-call", Map.of("method", "GET",
                "url", "https://api/${spec.name}"), "r"),
            step("compare-state", Map.of(
                "present-when", "${result.r.status} == 200",
                "absent-when", "${result.r.status} == 404"), null));
        var provisionSteps = List.of(
            step("assert", Map.of("condition", "true"), null));
        return new PluginModel(
            new PluginHeader("test-resource", 1, "30s", Map.of()),
            spec, actualSteps,
            new PluginProvisionerDef(provisionSteps, provisionSteps),
            List.of(), emptyCbr(), emptyRas());
    }

    private static PluginModel createPlugin(String type, PluginSpecSchema spec) {
        var actualSteps = List.of(
            step("compare-state", Map.of("present-when", "true"), null));
        var provisionSteps = List.of(
            step("assert", Map.of("condition", "true"), null));
        return new PluginModel(
            new PluginHeader(type, 1, null, Map.of()),
            spec, actualSteps,
            new PluginProvisionerDef(provisionSteps, provisionSteps),
            List.of(), emptyCbr(), emptyRas());
    }

    private static PluginModel createPluginWithSteps(String type, PluginSpecSchema spec,
                                                     List<PluginStepDef> actualSteps) {
        var provisionSteps = List.of(
            step("assert", Map.of("condition", "true"), null));
        return new PluginModel(
            new PluginHeader(type, 1, null, Map.of()),
            spec, actualSteps,
            new PluginProvisionerDef(provisionSteps, provisionSteps),
            List.of(), emptyCbr(), emptyRas());
    }

    private static PluginSpecSchema validSpec() {
        return new PluginSpecSchema(Map.of(
            "name", new PluginFieldDef("string", true, null, null, null, null,
                null, null, null, null, null, null, null)));
    }

    private static PluginStepDef step(String primitive, Map<String, Object> params,
                                      String result) {
        return new PluginStepDef(primitive, params, result, null, null, 3, null);
    }

    private static PluginCbrDef emptyCbr() {
        return new PluginCbrDef(List.of(), Map.of());
    }

    private static PluginRasDef emptyRas() {
        return new PluginRasDef(List.of());
    }
}
