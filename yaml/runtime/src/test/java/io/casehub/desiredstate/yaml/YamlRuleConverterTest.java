package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.annotations.runtime.GraphRuleEngine;
import io.casehub.desiredstate.annotations.runtime.PatternKind;
import io.casehub.desiredstate.annotations.runtime.ResolvedRule;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.yaml.model.YamlPattern;
import io.casehub.desiredstate.yaml.model.YamlRule;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import io.casehub.desiredstate.yaml.resolver.VariableResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlRuleConverterTest {

    record MonitorSpec(String target) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("monitor"); }
    }

    record SinkSpec(String destination) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("sink"); }
    }

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "sink", "io.casehub.desiredstate.yaml.YamlRuleConverterTest$SinkSpec",
            "monitor", "io.casehub.desiredstate.yaml.YamlRuleConverterTest$MonitorSpec");

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void toDeclarativeRule_addNodeAction_producesCorrectMutations() {
        YamlRule yamlRule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(),
                Map.of("guard", new YamlPattern("monitor", "sink", Direction.DEPENDENTS)),
                List.of(
                        Map.of("addNode", Map.of(
                                "id", "monitor-${match.sink.id}",
                                "type", "monitor",
                                "spec", Map.of("target", "${match.sink.id}"))),
                        Map.of("addDependency", Map.of(
                                "from", "monitor-${match.sink.id}",
                                "to", "${match.sink.id}"))));

        NodeSpecRegistry registry = NodeSpecRegistry.of(TYPE_REGISTRY);
        VariableResolver resolver = new VariableResolver(Map.of(), null, null);

        ResolvedRule.DeclarativeRule rule = YamlRuleConverter.toDeclarativeRule(
                "ensure-monitoring", yamlRule, resolver, registry);

        assertThat(rule.name()).isEqualTo("ensure-monitoring");
        assertThat(rule.patterns()).hasSize(2);
        assertThat(rule.patterns().get(0).kind()).isEqualTo(PatternKind.MATCH);
        assertThat(rule.patterns().get(1).kind()).isEqualTo(PatternKind.NOT_EXISTS);

        DesiredNode sinkNode = new DesiredNode(NodeId.of("warehouse-sink"),
                new SinkSpec("s3://gold/"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(sinkNode), List.of());

        var result = new GraphRuleEngine().evaluate(graph, List.of(rule));
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.nodes()).containsKey(NodeId.of("monitor-warehouse-sink"));

        MonitorSpec monSpec = (MonitorSpec) result.nodes()
                .get(NodeId.of("monitor-warehouse-sink")).spec();
        assertThat(monSpec.target()).isEqualTo("warehouse-sink");

        assertThat(result.dependencies()).contains(
                new Dependency(NodeId.of("monitor-warehouse-sink"),
                        NodeId.of("warehouse-sink")));
    }

    @Test
    void toDeclarativeRule_varInterpolationInSpec() {
        YamlRule yamlRule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(), Map.of(),
                List.of(Map.of("addNode", Map.of(
                        "id", "monitor-${match.sink.id}",
                        "type", "monitor",
                        "spec", Map.of("target", "${var.alert_prefix}-${match.sink.id}")))));

        NodeSpecRegistry registry = NodeSpecRegistry.of(TYPE_REGISTRY);
        VariableResolver resolver = new VariableResolver(Map.of("alert_prefix", "PROD"), null, null);

        ResolvedRule.DeclarativeRule rule = YamlRuleConverter.toDeclarativeRule(
                "alert-rule", yamlRule, resolver, registry);

        DesiredNode sinkNode = new DesiredNode(NodeId.of("my-sink"),
                new SinkSpec("s3://out/"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(sinkNode), List.of());

        var result = new GraphRuleEngine().evaluate(graph, List.of(rule));
        MonitorSpec monSpec = (MonitorSpec) result.nodes()
                .get(NodeId.of("monitor-my-sink")).spec();
        assertThat(monSpec.target()).isEqualTo("PROD-my-sink");
    }

    @Test
    void toDeclarativeRule_removeNodeAction() {
        YamlRule yamlRule = new YamlRule(
                List.of(),
                Map.of("sink", new YamlPattern("sink", null, Direction.DEPENDENCIES)),
                Map.of(), Map.of(), Map.of(),
                List.of(Map.of("removeNode", Map.of("id", "${match.sink.id}"))));

        NodeSpecRegistry registry = NodeSpecRegistry.of(TYPE_REGISTRY);
        VariableResolver resolver = new VariableResolver(Map.of(), null, null);

        ResolvedRule.DeclarativeRule rule = YamlRuleConverter.toDeclarativeRule(
                "remove-sinks", yamlRule, resolver, registry);

        DesiredNode sinkNode = new DesiredNode(NodeId.of("my-sink"),
                new SinkSpec("s3://out/"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(sinkNode), List.of());

        var result = new GraphRuleEngine().evaluate(graph, List.of(rule));
        assertThat(result.nodes()).isEmpty();
    }
}
