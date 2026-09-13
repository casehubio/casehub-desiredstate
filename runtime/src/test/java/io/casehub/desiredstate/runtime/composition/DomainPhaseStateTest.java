package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainPhaseStateTest {
    static final DesiredStateGraphFactory FACTORY = new DefaultDesiredStateGraphFactory();

    static DesiredStateGraph graphWith(String nodeId) {
        return FACTORY.empty().withNode(new DesiredNode(
            NodeId.of(nodeId), new TestSpec(nodeId), HumanGating.NONE));
    }

    record TestSpec(String name) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("test"); }
    }

    @Test void singleGraph_currentGraph() {
        var graph = graphWith("n1");
        var state = new DomainPhaseState(CompilationResult.single(graph), 0);
        assertThat(state.currentGraph().nodes()).containsKey(NodeId.of("n1"));
        assertThat(state.hasLifecycle()).isFalse();
        assertThat(state.isAtFinalPhase()).isTrue();
    }

    @Test void lifecycle_phaseAdvancement() {
        var g1 = graphWith("p1");
        var g2 = graphWith("p2");
        var lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("phase-1", g1, CompletionCondition.allPresent()),
            new Phase("phase-2", g2, CompletionCondition.allPresent())));
        var state = new DomainPhaseState(lifecycle, 0);
        assertThat(state.hasLifecycle()).isTrue();
        assertThat(state.isAtFinalPhase()).isFalse();
        assertThat(state.currentGraph().nodes()).containsKey(NodeId.of("p1"));

        var advanced = state.withAdvancedPhase();
        assertThat(advanced.phaseIndex()).isEqualTo(1);
        assertThat(advanced.currentGraph().nodes()).containsKey(NodeId.of("p2"));
        assertThat(advanced.isAtFinalPhase()).isTrue();
    }

    @Test void withResult_resetsPhaseIndex() {
        var g1 = graphWith("a");
        var g2 = graphWith("b");
        var lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("p1", g1, CompletionCondition.allPresent()),
            new Phase("p2", g2, CompletionCondition.allPresent())));
        var state = new DomainPhaseState(lifecycle, 1);
        var replaced = state.withResult(CompilationResult.single(graphWith("c")));
        assertThat(replaced.phaseIndex()).isEqualTo(0);
        assertThat(replaced.currentGraph().nodes()).containsKey(NodeId.of("c"));
    }
}
