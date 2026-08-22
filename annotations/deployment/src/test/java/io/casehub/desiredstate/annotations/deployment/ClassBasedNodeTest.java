package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ClassBasedNodeTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    TestLoadBalancer.class, TestDnsRecord.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    @DeclareNode(namespace = "test", name = "infra", id = "load-balancer")
    public static class TestLoadBalancer implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("lb"); }

        @Override
        public HumanGating humanGating() { return HumanGating.PROVISION_ONLY; }
    }

    @DeclareNode(namespace = "test", name = "infra", id = "dns-record")
    public static class TestDnsRecord implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("dns"); }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void classBasedNodesProduceGoalCompiler() {
        CompilationResult result = compiler.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);

        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(2);
    }

    @Test
    void nodeIdMatchesAnnotation() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("load-balancer"))).isNotNull();
        assertThat(graph.nodes().get(NodeId.of("dns-record"))).isNotNull();
    }

    @Test
    void nodeTypeFromNodeSpecMethod() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("load-balancer")).type())
                .isEqualTo(NodeType.of("lb"));
        assertThat(graph.nodes().get(NodeId.of("dns-record")).type())
                .isEqualTo(NodeType.of("dns"));
    }

    @Test
    void nodeSpecDataPreserved() {
        DesiredStateGraph graph = compileSingleGraph();
        assertThat(graph.nodes().get(NodeId.of("load-balancer")).spec())
                .isInstanceOf(TestLoadBalancer.class);
    }

    @Test
    void humanGatingFromNodeSpecOverride() {
        DesiredStateGraph graph = compileSingleGraph();
        DesiredNode lb = graph.nodes().get(NodeId.of("load-balancer"));
        assertThat(lb.humanGating()).isEqualTo(HumanGating.PROVISION_ONLY);
        assertThat(lb.requiresHuman()).isTrue();

        DesiredNode dns = graph.nodes().get(NodeId.of("dns-record"));
        assertThat(dns.humanGating()).isEqualTo(HumanGating.NONE);
        assertThat(dns.requiresHuman()).isFalse();
    }

    @Test
    void classOnlyGraphIgnoresGoals() {
        CompilationResult result1 = compiler.compile(null, factory);
        CompilationResult result2 = compiler.compile("ignored", factory);
        DesiredStateGraph g1 = ((CompilationResult.SingleGraph) result1).graph();
        DesiredStateGraph g2 = ((CompilationResult.SingleGraph) result2).graph();
        assertThat(g1.nodes().keySet()).isEqualTo(g2.nodes().keySet());
    }

    private DesiredStateGraph compileSingleGraph() {
        return ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
    }
}
