package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.DesiredStateQualifier;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class QualifierMultiGraphTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    GraphA.class, GraphB.class, SpecA.class, SpecB.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record SpecA(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("type-a"); }
    }

    public record SpecB(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("type-b"); }
    }

    @DesiredState(namespace = "multi", name = "graph-a")
    public interface GraphA {
        @Node("node-a")
        default SpecA nodeA() { return new SpecA("a"); }
    }

    @DesiredState(namespace = "multi", name = "graph-b")
    public interface GraphB {
        @Node("node-b")
        default SpecB nodeB() { return new SpecB("b"); }
    }

    @SuppressWarnings("unchecked")
    @Inject
    @DesiredStateQualifier(namespace = "multi", name = "graph-a")
    GoalCompiler compilerA;

    @SuppressWarnings("unchecked")
    @Inject
    @DesiredStateQualifier(namespace = "multi", name = "graph-b")
    GoalCompiler compilerB;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void eachQualifierResolvesCorrectGraph() {
        DesiredStateGraph graphA = ((CompilationResult.SingleGraph) compilerA.compile(null, factory)).graph();
        DesiredStateGraph graphB = ((CompilationResult.SingleGraph) compilerB.compile(null, factory)).graph();

        assertThat(graphA.nodes()).containsKey(NodeId.of("node-a"));
        assertThat(graphA.nodes()).doesNotContainKey(NodeId.of("node-b"));

        assertThat(graphB.nodes()).containsKey(NodeId.of("node-b"));
        assertThat(graphB.nodes()).doesNotContainKey(NodeId.of("node-a"));
    }

    @Test
    void eachGraphHasOneNode() {
        DesiredStateGraph graphA = ((CompilationResult.SingleGraph) compilerA.compile(null, factory)).graph();
        DesiredStateGraph graphB = ((CompilationResult.SingleGraph) compilerB.compile(null, factory)).graph();
        assertThat(graphA.nodes()).hasSize(1);
        assertThat(graphB.nodes()).hasSize(1);
    }
}
