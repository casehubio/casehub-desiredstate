package io.casehub.desiredstate.ts;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TsOrderingConstraintE2eTest {

    @NodeTypeId("sensor")
    public record SensorSpec(String value) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("sensor"); }
    }

    @NodeTypeId("aggregator")
    public record AggregatorSpec(String value) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("aggregator"); }
    }

    private static final Map<String, String> TYPE_REGISTRY = Map.of(
            "sensor", SensorSpec.class.getName(),
            "aggregator", AggregatorSpec.class.getName());

    @Test
    void tsDsl_orderingConstraint_enforcesLayerOrdering() {
        TsEnvelope envelope = new TsEnvelope("single", "test", "iot",
                List.of(
                        new TsEnvelopeNode("temp-sensor", "sensor",
                                Map.of("value", "temperature"), HumanGating.NONE, null),
                        new TsEnvelopeNode("data-agg", "aggregator",
                                Map.of("value", "rollup"), HumanGating.NONE, null)),
                List.of(),
                List.of(new TsOrderingConstraint("sensor", "aggregator")));

        TsDslDiscovery discovery = new TsDslDiscovery();
        List<BeanRegistration> beans = discovery.discover(
                List.of(new TsDslDiscovery.DiscoveredEnvelope("iot", envelope, null)),
                TYPE_REGISTRY);

        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = (GoalCompiler<Void>) beans.get(0).instance();
        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();

        TransitionPlanner planner = new TransitionPlanner();
        TransitionPlan plan = planner.plan(graph, new ActualState(Map.of()));

        assertThat(plan.additions().size()).isGreaterThanOrEqualTo(2);

        List<NodeId> order = plan.flatAdditions().stream()
                .map(s -> s.node().id()).toList();
        assertThat(order.indexOf(NodeId.of("temp-sensor")))
                .isLessThan(order.indexOf(NodeId.of("data-agg")));
    }
}
