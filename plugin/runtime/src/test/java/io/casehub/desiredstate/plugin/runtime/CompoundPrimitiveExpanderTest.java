package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.plugin.model.CompoundPrimitiveDef;
import io.casehub.desiredstate.plugin.model.PluginFieldDef;
import io.casehub.desiredstate.plugin.model.PluginStepDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompoundPrimitiveExpanderTest {

    private static final Set<String> JAVA_PRIMITIVES = Set.of(
        "rest-call", "assert", "compare-state", "json-extract");

    @Test
    void expandsCompoundToFlatSteps() {
        var compound = new CompoundPrimitiveDef("k8s-call",
            Map.of("method", fieldDef("string"), "path", fieldDef("string")),
            List.of(step("rest-call", Map.of(
                "method", "${param.method}",
                "url", "https://k8s.api${param.path}"), "response")),
            "response");

        var expander = new CompoundPrimitiveExpander(
            Map.of("k8s-call", compound), JAVA_PRIMITIVES);

        var invocation = step("k8s-call", Map.of(
            "method", "GET", "path", "/apis/v1/pods"), "r");

        var expanded = expander.expand(invocation);

        assertThat(expanded).hasSize(1);
        assertThat(expanded.get(0).primitiveName()).isEqualTo("rest-call");
        assertThat(expanded.get(0).parameters().get("method")).isEqualTo("GET");
        assertThat(expanded.get(0).parameters().get("url"))
            .isEqualTo("https://k8s.api/apis/v1/pods");
        assertThat(expanded.get(0).resultName()).isEqualTo("r");
    }

    @Test
    void javaPrimitivePassesThrough() {
        var expander = new CompoundPrimitiveExpander(Map.of(), JAVA_PRIMITIVES);
        var step = step("rest-call", Map.of("method", "GET"), "r");

        var expanded = expander.expand(step);
        assertThat(expanded).hasSize(1);
        assertThat(expanded.get(0)).isEqualTo(step);
    }

    @Test
    void detectsCycles() {
        var a = new CompoundPrimitiveDef("a",
            Map.of(), List.of(step("b", Map.of(), null)), null);
        var b = new CompoundPrimitiveDef("b",
            Map.of(), List.of(step("a", Map.of(), null)), null);

        var expander = new CompoundPrimitiveExpander(
            Map.of("a", a, "b", b), JAVA_PRIMITIVES);

        assertThatThrownBy(() -> expander.expand(step("a", Map.of(), null)))
            .isInstanceOf(CompoundPrimitiveExpander.CyclicPrimitiveException.class);
    }

    @Test
    void enforcesMaxDepth() {
        var a = new CompoundPrimitiveDef("a",
            Map.of(), List.of(step("b", Map.of(), null)), null);
        var b = new CompoundPrimitiveDef("b",
            Map.of(), List.of(step("c", Map.of(), null)), null);
        var c = new CompoundPrimitiveDef("c",
            Map.of(), List.of(step("d", Map.of(), null)), null);
        var d = new CompoundPrimitiveDef("d",
            Map.of(), List.of(step("e", Map.of(), null)), null);
        var e = new CompoundPrimitiveDef("e",
            Map.of(), List.of(step("f", Map.of(), null)), null);
        var f = new CompoundPrimitiveDef("f",
            Map.of(), List.of(step("rest-call", Map.of(), null)), null);

        var expander = new CompoundPrimitiveExpander(
            Map.of("a", a, "b", b, "c", c, "d", d, "e", e, "f", f),
            JAVA_PRIMITIVES, 5);

        assertThatThrownBy(() -> expander.expand(step("a", Map.of(), null)))
            .isInstanceOf(CompoundPrimitiveExpander.MaxPrimitiveDepthException.class);
    }

    @Test
    void parameterScopingInnermostWins() {
        var inner = new CompoundPrimitiveDef("inner",
            Map.of("x", fieldDef("string")),
            List.of(step("assert", Map.of("condition", "${param.x} == 2"), null)),
            null);

        var outer = new CompoundPrimitiveDef("outer",
            Map.of("x", fieldDef("string")),
            List.of(step("inner", Map.of("x", "2"), null)),
            null);

        var expander = new CompoundPrimitiveExpander(
            Map.of("inner", inner, "outer", outer), JAVA_PRIMITIVES);

        var expanded = expander.expand(step("outer", Map.of("x", "1"), null));

        assertThat(expanded).hasSize(1);
        assertThat(expanded.get(0).parameters().get("condition")).isEqualTo("2 == 2");
    }

    @Test
    void multiStepCompoundExpandsAll() {
        var compound = new CompoundPrimitiveDef("two-step",
            Map.of("url", fieldDef("string")),
            List.of(
                step("rest-call", Map.of("method", "GET", "url", "${param.url}"), "r"),
                step("assert", Map.of("condition", "true"), null)),
            null);

        var expander = new CompoundPrimitiveExpander(
            Map.of("two-step", compound), JAVA_PRIMITIVES);

        var expanded = expander.expand(step("two-step", Map.of("url", "https://api"), null));

        assertThat(expanded).hasSize(2);
        assertThat(expanded.get(0).primitiveName()).isEqualTo("rest-call");
        assertThat(expanded.get(1).primitiveName()).isEqualTo("assert");
    }

    @Test
    void nestedMapParameterSubstitution() {
        var compound = new CompoundPrimitiveDef("with-body",
            Map.of("name", fieldDef("string")),
            List.of(step("rest-call", Map.of(
                "method", "PUT",
                "body", Map.of("name", "${param.name}")), "r")),
            null);

        var expander = new CompoundPrimitiveExpander(
            Map.of("with-body", compound), JAVA_PRIMITIVES);

        var expanded = expander.expand(
            step("with-body", Map.of("name", "my-app"), null));

        assertThat(expanded).hasSize(1);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) expanded.get(0).parameters().get("body");
        assertThat(body.get("name")).isEqualTo("my-app");
    }

    private static PluginStepDef step(String primitive, Map<String, Object> params,
                                      String result) {
        return new PluginStepDef(primitive, params, result, null, null, 3, null);
    }

    private static PluginFieldDef fieldDef(String type) {
        return new PluginFieldDef(type, true, null, null, null, null,
            null, null, null, null, null, null, null);
    }
}
