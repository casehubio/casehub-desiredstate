package io.casehub.desiredstate.yaml.deployment;

import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.yaml.model.YamlPattern;
import io.casehub.desiredstate.yaml.model.YamlRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlRuleValidationTest {

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "sink", "com.example.SinkSpec",
            "monitor", "com.example.MonitorSpec");

    @Test
    void validate_validRule_passes() {
        YamlRule rule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(),
                Map.of("guard", new YamlPattern("monitor", "sink", Direction.DEPENDENTS)),
                List.of(Map.of("addNode", Map.of(
                        "id", "monitor-${match.sink.id}",
                        "type", "monitor",
                        "spec", Map.of("target", "${match.sink.id}")))));
        assertThatCode(() -> YamlDesiredStateProcessor.validateRule(
                "ensure-monitoring", rule, TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_emptyMatch_throwsBuildError() {
        YamlRule rule = new YamlRule(
                List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(Map.of("removeNode", Map.of("id", "x"))));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateRule(
                "empty-match", rule, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("match");
    }

    @Test
    void validate_emptyActions_throwsBuildError() {
        YamlRule rule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(), Map.of(),
                List.of());
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateRule(
                "empty-actions", rule, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("actions");
    }

    @Test
    void validate_unknownActionType_throwsBuildError() {
        YamlRule rule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(), Map.of(),
                List.of(Map.of("destroyNode", Map.of("id", "x"))));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateRule(
                "bad-action", rule, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("destroyNode")
                .hasMessageContaining("addNode");
    }

    @Test
    void validate_addNodeUnknownType_throwsBuildError() {
        YamlRule rule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(), Map.of(),
                List.of(Map.of("addNode", Map.of(
                        "id", "x", "type", "nonexistent", "spec", Map.of()))));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateRule(
                "bad-type", rule, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("nonexistent");
    }

    @Test
    void validate_addNodeMissingType_throwsBuildError() {
        YamlRule rule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(), Map.of(),
                List.of(Map.of("addNode", Map.of("id", "x", "spec", Map.of()))));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateRule(
                "no-type", rule, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("type");
    }

    @Test
    void validate_ofReferencesUnknownBinding_throwsBuildError() {
        YamlRule rule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of("dep", new YamlPattern("monitor", "nonexistent", Direction.DEPENDENCIES)),
                Map.of(), Map.of(),
                List.of(Map.of("removeNode", Map.of("id", "x"))));
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateRule(
                "bad-of", rule, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("nonexistent");
    }
}
