package io.casehub.desiredstate.plugin.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginInterpolatorTest {

    private final PluginInterpolator interpolator = new PluginInterpolator();

    @Test
    void interpolateSpecField() {
        var ctx = StepContext.builder()
            .spec(Map.of("namespace", "default", "replicas", 3))
            .build();

        assertThat(interpolator.interpolate("ns=${spec.namespace}", ctx))
            .isEqualTo("ns=default");
        assertThat(interpolator.interpolate("count=${spec.replicas}", ctx))
            .isEqualTo("count=3");
    }

    @Test
    void interpolateAuthField() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addAuth("k8s", Map.of("endpoint", "api.k8s.io", "token", "secret"))
            .build();

        assertThat(interpolator.interpolate("https://${auth.k8s.endpoint}/api", ctx))
            .isEqualTo("https://api.k8s.io/api");
    }

    @Test
    void interpolateResultField() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("response", StepResult.of(Map.of("status", 200)))
            .build();

        assertThat(interpolator.interpolate("code=${result.response.status}", ctx))
            .isEqualTo("code=200");
    }

    @Test
    void interpolateDeepResultPath() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .addResult("response", StepResult.of(Map.of(
                "body", Map.of("status", Map.of("replicas", 3)))))
            .build();

        assertThat(interpolator.interpolate("${result.response.body.status.replicas}", ctx))
            .isEqualTo("3");
    }

    @Test
    void interpolateParamField() {
        var ctx = StepContext.builder()
            .spec(Map.of())
            .params(Map.of("method", "GET"))
            .build();

        assertThat(interpolator.interpolate("${param.method}", ctx))
            .isEqualTo("GET");
    }

    @Test
    void evaluateConditionWithContext() {
        var ctx = StepContext.builder()
            .spec(Map.of("replicas", 3))
            .addResult("r", StepResult.of(Map.of("status", 200)))
            .build();

        assertThat(interpolator.evaluateCondition("${result.r.status} == 200", ctx))
            .isTrue();
        assertThat(interpolator.evaluateCondition("${spec.replicas} > 5", ctx))
            .isFalse();
    }

    @Test
    void interpolateMapRecursively() {
        var ctx = StepContext.builder()
            .spec(Map.of("name", "app", "replicas", 3))
            .build();

        var template = new HashMap<String, Object>();
        template.put("metadata", new HashMap<>(Map.of("name", "${spec.name}")));
        template.put("replicas", "${spec.replicas}");
        template.put("literal", "unchanged");

        var resolved = interpolator.interpolateMap(template, ctx);

        @SuppressWarnings("unchecked")
        var metadata = (Map<String, Object>) resolved.get("metadata");
        assertThat(metadata.get("name")).isEqualTo("app");
        assertThat(resolved.get("replicas")).isEqualTo("3");
        assertThat(resolved.get("literal")).isEqualTo("unchanged");
    }

    @Test
    void interpolateListValues() {
        var ctx = StepContext.builder()
            .spec(Map.of("image", "nginx:latest"))
            .build();

        var template = new HashMap<String, Object>();
        template.put("containers", List.of(new HashMap<>(Map.of("image", "${spec.image}"))));

        var resolved = interpolator.interpolateMap(template, ctx);

        @SuppressWarnings("unchecked")
        var containers = (List<Object>) resolved.get("containers");
        @SuppressWarnings("unchecked")
        var container = (Map<String, Object>) containers.get(0);
        assertThat(container.get("image")).isEqualTo("nginx:latest");
    }

    @Test
    void unknownPrefixThrows() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThatThrownBy(() -> interpolator.interpolate("${unknown.field}", ctx))
            .isInstanceOf(InterpolationException.class)
            .hasMessageContaining("Unknown prefix");
    }

    @Test
    void missingFieldReturnsNullString() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThat(interpolator.interpolate("${spec.missing}", ctx))
            .isEqualTo("null");
    }

    @Test
    void multipleInterpolationsInOneString() {
        var ctx = StepContext.builder()
            .spec(Map.of("ns", "default", "name", "app"))
            .build();

        assertThat(interpolator.interpolate("/namespaces/${spec.ns}/pods/${spec.name}", ctx))
            .isEqualTo("/namespaces/default/pods/app");
    }

    @Test
    void noInterpolationReturnsOriginal() {
        var ctx = StepContext.builder().spec(Map.of()).build();

        assertThat(interpolator.interpolate("plain text", ctx))
            .isEqualTo("plain text");
    }
}
