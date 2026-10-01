package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoalCompilerFactoryOrderingTest {

    @NodeTypeId("source")
    public record SourceSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("source");}
    }

    @NodeTypeId("sink")
    public record SinkSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("sink");}
    }

    @Test
    void orderingConstraints_appliedToCompiledGraph() {
        var descriptor = new GraphDescriptor(
                "test", "ordered", null, null,
                List.of(
                        new NodeDescriptor.ClassNode("src", SourceSpec.class.getName()),
                        new NodeDescriptor.ClassNode("dst", SinkSpec.class.getName())
                       ),
                List.of(), List.of(), null, List.of(), List.of(),
                List.of(new OrderingConstraintDescriptor("source", "sink")));

        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = (GoalCompiler<Void>) GoalCompilerFactory.create(descriptor);
        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph  = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.orderingConstraints()).hasSize(1);
        var constraint = graph.orderingConstraints().iterator().next();
        assertThat(constraint.before()).isEqualTo(NodeType.of("source"));
        assertThat(constraint.after()).isEqualTo(NodeType.of("sink"));
    }

    @Test
    void noOrderingConstraints_graphHasEmptySet() {
        var descriptor = new GraphDescriptor(
                "test", "plain", null, null,
                List.of(
                        new NodeDescriptor.ClassNode("src", SourceSpec.class.getName())
                       ),
                List.of(), List.of(), null, List.of(), List.of());

        @SuppressWarnings("unchecked")
        GoalCompiler<Void> compiler = (GoalCompiler<Void>) GoalCompilerFactory.create(descriptor);
        CompilationResult result = compiler.compile(null, new DefaultDesiredStateGraphFactory());
        DesiredStateGraph graph  = ((CompilationResult.SingleGraph) result).graph();

        assertThat(graph.nodes()).hasSize(1);
        assertThat(graph.orderingConstraints()).isEmpty();
    }
}
