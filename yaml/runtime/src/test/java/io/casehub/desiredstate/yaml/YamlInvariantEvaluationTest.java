package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantEngine;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantViolationsException;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlPattern;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlInvariantEvaluationTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();
    private final GraphInvariantEngine engine = new GraphInvariantEngine();

    record Spec(String name, String typeValue) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of(typeValue); }
    }

    @Test
    void declarativeInvariant_violated_whenSinkHasNoUpstream() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("tx-1"), new Spec("t1", "transformer"), HumanGating.NONE)),
                List.of());

        YamlInvariant yamlInv = new YamlInvariant(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of("upstream", new YamlPattern("transformer", "sink", Direction.DEPENDENCIES)),
                Map.of(), Map.of(),
                "Sink ${match.sink.id} has no upstream transformer");

        ResolvedInvariant invariant = YamlInvariantConverter.toDeclarativeInvariant(
                "every-sink-has-upstream", yamlInv);

        var ex = assertThrows(GraphInvariantViolationsException.class,
                () -> engine.validate(graph, List.of(invariant)));
        assertThat(ex.violations()).hasSize(1);
        assertThat(ex.violations().get(0).message()).contains("sink-1");
        assertThat(ex.violations().get(0).message()).contains("no upstream transformer");
    }

    @Test
    void declarativeInvariant_passes_whenSinkHasUpstream() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("tx-1"), new Spec("t1", "transformer"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("sink-1"), NodeId.of("tx-1"))));

        YamlInvariant yamlInv = new YamlInvariant(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of("upstream", new YamlPattern("transformer", "sink", Direction.DEPENDENCIES)),
                Map.of(), Map.of(), null);

        ResolvedInvariant invariant = YamlInvariantConverter.toDeclarativeInvariant(
                "every-sink-has-upstream", yamlInv);

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    @Test
    void declarativeInvariant_vacuouslyTrue_whenNoMatchingAnchors() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("tx-1"), new Spec("t1", "transformer"), HumanGating.NONE)),
                List.of());

        YamlInvariant yamlInv = new YamlInvariant(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of("upstream", new YamlPattern("transformer", "sink", Direction.DEPENDENCIES)),
                Map.of(), Map.of(), null);

        ResolvedInvariant invariant = YamlInvariantConverter.toDeclarativeInvariant(
                "every-sink-has-upstream", yamlInv);

        assertDoesNotThrow(() -> engine.validate(graph, List.of(invariant)));
    }

    @Test
    void declarativeInvariant_notExists_violated_whenRelationExists() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("tx"), new Spec("tx", "transformer"), HumanGating.NONE),
                        new DesiredNode(NodeId.of("val"), new Spec("val", "validator"), HumanGating.NONE)),
                List.of(new Dependency(NodeId.of("val"), NodeId.of("tx"))));

        YamlInvariant yamlInv = new YamlInvariant(
                List.of(),
                Map.of("tx", new YamlPattern("transformer", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(),
                Map.of("guard", new YamlPattern("validator", "tx", Direction.DEPENDENTS)),
                "Transformer ${match.tx.id} should not have a validator");

        ResolvedInvariant invariant = YamlInvariantConverter.toDeclarativeInvariant(
                "no-validator-on-transformer", yamlInv);

        var ex = assertThrows(GraphInvariantViolationsException.class,
                () -> engine.validate(graph, List.of(invariant)));
        assertThat(ex.violations()).hasSize(1);
    }

    @Test
    void declarativeInvariant_defaultMessage_whenTemplateNull() {
        DesiredStateGraph graph = factory.of(
                List.of(
                        new DesiredNode(NodeId.of("sink-1"), new Spec("s1", "sink"), HumanGating.NONE)),
                List.of());

        YamlInvariant yamlInv = new YamlInvariant(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of("upstream", new YamlPattern("transformer", "sink", Direction.DEPENDENCIES)),
                Map.of(), Map.of(), null);

        ResolvedInvariant invariant = YamlInvariantConverter.toDeclarativeInvariant(
                "every-sink-has-upstream", yamlInv);

        var ex = assertThrows(GraphInvariantViolationsException.class,
                () -> engine.validate(graph, List.of(invariant)));
        assertThat(ex.violations().get(0).message()).contains("every-sink-has-upstream");
        assertThat(ex.violations().get(0).message()).contains("sink-1");
    }
}
