package io.casehub.desiredstate.yaml.deployment;

import io.casehub.desiredstate.yaml.model.YamlDesiredState;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlLifecycle;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.model.YamlPhase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlLifecycleValidationTest {

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "db", "com.example.DbSpec",
            "app", "com.example.AppSpec");

    @Test
    void validate_validLifecycle_passes() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "valid"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("db", new YamlNode("db", Map.of(), List.of(), null, null, null, null, null))),
                        new YamlPhase("app", "never",
                                Map.of("api", new YamlNode("app", Map.of(), List.of(), null, null, null, null, null))))),
                null, null);
        assertThatCode(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_lifecycleWithImports_passes() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "with-imports"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                      Map.of("db", new YamlNode("db", Map.of(), List.of(), null, null, null, null, null))))),
                null,
                List.of(new io.casehub.desiredstate.yaml.model.YamlImport(
                        "load-balancer", "lb", null, Map.of("target_service", "web", "namespace", "prod"))));
        assertThatCode(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();}


    @Test
    void validate_lifecycleAndTopLevelNodes_throwsBuildError() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "conflict"),
                Map.of(),
                Map.of("app", new YamlNode("app", Map.of(), List.of(), null, null, null, null, null)),
                List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("db", new YamlNode("db", Map.of(), List.of(), null, null, null, null, null))))),
                null, null);
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("nodes")
                .hasMessageContaining("lifecycle");
    }

    @Test
    void validate_emptyPhases_throwsBuildError() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "empty"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of()),
                null, null);
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("phase");
    }

    @Test
    void validate_unknownCompletionCondition_throwsBuildError() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "bad-cc"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "whenReady",
                                Map.of("db", new YamlNode("db", Map.of(), List.of(), null, null, null, null, null))))),
                null, null);
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("whenReady")
                .hasMessageContaining("allPresent");
    }

    @Test
    void validate_duplicatePhaseIds_throwsBuildError() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "dup-phase"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", "allPresent",
                                Map.of("db", new YamlNode("db", Map.of(), List.of(), null, null, null, null, null))),
                        new YamlPhase("infra", "never",
                                Map.of("app", new YamlNode("app", Map.of(), List.of(), null, null, null, null, null))))),
                null, null);
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("infra")
                .hasMessageContaining("duplicate");
    }

    @Test
    void validate_noLifecycle_passes() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "no-lifecycle"),
                Map.of(),
                Map.of("app", new YamlNode("app", Map.of(), List.of(), null, null, null, null, null)),
                List.of(), Map.of(), Map.of(), null, null, null);
        assertThatCode(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_missingCompletionCondition_throwsBuildError() {
        var graph = new YamlGraph(
                new YamlDesiredState("test", "no-cc"),
                Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                new YamlLifecycle(List.of(
                        new YamlPhase("infra", null,
                                Map.of("db", new YamlNode("db", Map.of(), List.of(), null, null, null, null, null))))),
                null, null);
        assertThatThrownBy(() -> YamlDesiredStateProcessor.validateLifecycle(
                graph, TYPE_REGISTRY, "test.yaml"))
                .hasMessageContaining("completionCondition");
    }
}
