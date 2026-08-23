package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DeclareNode;
import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
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

class InterfaceNodesDependencyTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    InterfaceWithClassDep.class, ClassTarget.class, ISpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record ISpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("i"); }
    }

    @DeclareNode(namespace = "iface-dep", name = "test", id = "class-target")
    public static class ClassTarget implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("ct"); }
    }

    @DesiredState(namespace = "iface-dep", name = "test")
    public interface InterfaceWithClassDep {
        @Node("iface-node")
        @DependsOn(nodes = ClassTarget.class)
        default ISpec ifaceNode() { return new ISpec("iface"); }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler compiler;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void interfaceNodesDependencyResolvedToClassTarget() {
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
        assertThat(graph.nodes()).hasSize(2);
        assertThat(graph.dependencies())
                .contains(new Dependency(NodeId.of("iface-node"), NodeId.of("class-target")));
    }

    @Test
    void noNpeWhenOnlyNodesAttributeUsed() {
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) compiler.compile(null, factory)).graph();
        assertThat(graph.nodes().get(NodeId.of("iface-node"))).isNotNull();
    }
}
