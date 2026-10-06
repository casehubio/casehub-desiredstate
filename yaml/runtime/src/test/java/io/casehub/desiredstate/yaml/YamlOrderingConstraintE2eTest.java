package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.yaml.jackson.YamlMappers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlOrderingConstraintE2eTest {

    @NodeTypeId("bronze")
    public record BronzeSpec(String name) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("bronze"); }
    }

    @NodeTypeId("silver")
    public record SilverSpec(String name) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("silver"); }
    }

    @Test
    void yaml_orderingConstraint_enforcesLayerOrdering() throws Exception {
        String yaml = """
                desiredState:
                  namespace: test
                  name: pipeline
                nodes:
                  ingest:
                    type: bronze
                    spec:
                      name: raw-ingest
                  transform:
                    type: silver
                    spec:
                      name: clean-transform
                orderingConstraints:
                  - before: bronze
                    after: silver
                """;

        ObjectMapper yamlMapper = YamlMappers.create();
        YamlGraph yamlGraph = yamlMapper.readValue(yaml, YamlGraph.class);

        Map<String, String> typeRegistry = Map.of(
                "bronze", BronzeSpec.class.getName(),
                "silver", SilverSpec.class.getName());

        YamlDiscovery discovery = new YamlDiscovery();
        var beans = discovery.discover(List.of(yamlGraph), typeRegistry, Map.of());
        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = (GoalCompiler<Void>) beans.get(0).instance();

        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

        TransitionPlanner planner = new TransitionPlanner();
        TransitionPlan plan = planner.plan(graph, new ActualState(Map.of()));

        assertThat(plan.additions().size()).isGreaterThanOrEqualTo(2);

        List<NodeId> order = plan.flatAdditions().stream()
                .map(s -> s.node().id()).toList();
        assertThat(order.indexOf(NodeId.of("ingest")))
                .isLessThan(order.indexOf(NodeId.of("transform")));
    }
}
