package io.casehub.desiredstate.annotations.runtime;

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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationOrderingConstraintE2eTest {

    @NodeTypeId("ingress")
    public record IngressSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("ingress");}
    }

    @NodeTypeId("processor")
    public record ProcessorSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("processor");}
    }

    @Test
    void annotation_orderingConstraint_enforcesLayerOrdering() {
        var descriptor = new GraphDescriptor(
                "test", "ordered", null, null,
                List.of(
                        new NodeDescriptor.ClassNode("gw", IngressSpec.class.getName()),
                        new NodeDescriptor.ClassNode("worker", ProcessorSpec.class.getName())
                       ),
                List.of(), List.of(), null, List.of(), List.of(),
                List.of(new OrderingConstraintDescriptor("ingress", "processor")));

        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = (GoalCompiler<Void>) GoalCompilerFactory.create(descriptor);
        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph  = ((CompilationResult.SingleGraph) result).graph();

        TransitionPlanner planner = new TransitionPlanner();
        TransitionPlan    plan    = planner.plan(graph, new ActualState(Map.of()));

        assertThat(plan.additions().size()).isGreaterThanOrEqualTo(2);

        List<NodeId> order = plan.flatAdditions().stream()
                                 .map(s -> s.node().id()).toList();
        assertThat(order.indexOf(NodeId.of("gw")))
                .isLessThan(order.indexOf(NodeId.of("worker")));
    }
}
