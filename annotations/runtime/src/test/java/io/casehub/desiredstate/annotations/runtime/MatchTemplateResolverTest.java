package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatchTemplateResolverTest {

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    private final Map<String, DesiredNode> bindings = Map.of(
            "sink", new DesiredNode(NodeId.of("warehouse-sink"),
                    new Spec("ws", "sink"), HumanGating.NONE),
            "src", new DesiredNode(NodeId.of("pipe-monitor.monitor"),
                    new Spec("mon", "monitor"), HumanGating.NONE));

    @Test
    void resolveId() {
        assertThat(MatchTemplateResolver.resolve("monitor-${match.sink.id}", bindings))
                .isEqualTo("monitor-warehouse-sink");
    }

    @Test
    void resolveType() {
        assertThat(MatchTemplateResolver.resolve("type-is-${match.sink.type}", bindings))
                .isEqualTo("type-is-sink");
    }

    @Test
    void resolveFlatId() {
        assertThat(MatchTemplateResolver.resolve("health-${match.src.flatId}", bindings))
                .isEqualTo("health-pipe-monitor-monitor");
    }

    @Test
    void resolveMultipleBindings() {
        assertThat(MatchTemplateResolver.resolve(
                "${match.sink.id}-to-${match.src.type}", bindings))
                .isEqualTo("warehouse-sink-to-monitor");
    }

    @Test
    void noTemplatesPassesThrough() {
        assertThat(MatchTemplateResolver.resolve("literal-id", bindings))
                .isEqualTo("literal-id");
    }

    @Test
    void resolveNodeId_rejectsDotInResult() {
        assertThatThrownBy(() -> MatchTemplateResolver.resolveNodeId(
                "health-${match.src.id}", bindings, "add-health-check"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".")
                .hasMessageContaining("add-health-check");
    }

    @Test
    void resolveNodeId_acceptsFlatId() {
        assertThat(MatchTemplateResolver.resolveNodeId(
                "health-${match.src.flatId}", bindings, "add-health-check"))
                .isEqualTo("health-pipe-monitor-monitor");
    }

    @Test
    void unknownBinding_throws() {
        assertThatThrownBy(() -> MatchTemplateResolver.resolve(
                "${match.unknown.id}", bindings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }
}
