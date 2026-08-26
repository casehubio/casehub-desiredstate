package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.GraphRule;
import io.casehub.desiredstate.annotations.Match;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.NotExists;
import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.GraphMutations;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

class GraphRuleProcessorTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    RuleGraph.class, TxSpec.class, SinkSpec.class, MonitorSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record TxSpec(String name) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("transformer"); }
    }

    public record SinkSpec(String name) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("sink"); }
    }

    public record MonitorSpec(String target) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("monitor"); }
    }

    @DesiredState(namespace = "test", name = "rules")
    public interface RuleGraph {
        @Node("tx1")
        default TxSpec tx1() { return new TxSpec("tx1"); }

        @Node("sink1")
        @DependsOn("tx1")
        default SinkSpec sink1() { return new SinkSpec("sink1"); }

        @GraphRule
        static List<GraphMutation> ensureMonitoring(
                @Match(type = "sink") DesiredNode sink,
                @NotExists(type = "monitor", of = "sink", direction = Direction.DEPENDENTS) Void guard) {
            return GraphMutations.addNodeDependingOn(
                    new DesiredNode(NodeId.of("monitor-" + sink.id().value()),
                            new MonitorSpec(sink.id().value()), HumanGating.NONE),
                    sink.id());
        }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void graphRuleAddsNodesAtCompileTime() {
        CompilationResult result = compiler.compile(null, factory);
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).containsKey(NodeId.of("monitor-sink1"));
        assertThat(graph.nodes().get(NodeId.of("monitor-sink1")).type())
                .isEqualTo(NodeType.of("monitor"));
    }

    @Test
    void graphRuleCreatesCorrectDependency() {
        CompilationResult result = compiler.compile(null, factory);
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.dependenciesOf(NodeId.of("monitor-sink1")))
                .contains(NodeId.of("sink1"));
    }

    @Test
    void graphRuleDoesNotAffectOriginalNodes() {
        CompilationResult result = compiler.compile(null, factory);
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.nodes()).containsKey(NodeId.of("tx1"));
        assertThat(graph.nodes()).containsKey(NodeId.of("sink1"));
    }
}
