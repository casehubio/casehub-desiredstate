package io.casehub.desiredstate.annotations.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.DesiredStateQualifier;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class QualifierSingleGraphTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    QualifiedGraph.class, QSpec.class))
            .overrideConfigKey("quarkus.arc.exclude-types",
                    "io.casehub.desiredstate.runtime.**");

    public record QSpec(String data) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("q"); }
    }

    @DesiredState(namespace = "qual", name = "single")
    public interface QualifiedGraph {
        @Node("q-node")
        default QSpec qNode() { return new QSpec("data"); }
    }

    @SuppressWarnings("unchecked")
    @Inject
    GoalCompiler unqualified;

    @SuppressWarnings("unchecked")
    @Inject
    @DesiredStateQualifier(namespace = "qual", name = "single")
    GoalCompiler qualified;

    private final DesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @Test
    void unqualifiedInjectionStillResolves() {
        CompilationResult result = unqualified.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(1);
    }

    @Test
    void qualifiedInjectionResolves() {
        CompilationResult result = qualified.compile(null, factory);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        DesiredStateGraph graph = ((CompilationResult.SingleGraph) result).graph();
        assertThat(graph.nodes()).hasSize(1);
    }

    @Test
    void bothInjectionsReturnSameGraph() {
        DesiredStateGraph g1 = ((CompilationResult.SingleGraph) unqualified.compile(null, factory)).graph();
        DesiredStateGraph g2 = ((CompilationResult.SingleGraph) qualified.compile(null, factory)).graph();
        assertThat(g1.nodes().keySet()).isEqualTo(g2.nodes().keySet());
    }
}
