package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CompilationResultTest {

    private final DesiredStateGraph emptyGraph = new TestGraph();
    private final DesiredStateGraph otherGraph = new TestGraph();

    @Test
    void singleGraph_wrapsGraph() {
        CompilationResult result = CompilationResult.single(emptyGraph);
        assertThat(result).isInstanceOf(CompilationResult.SingleGraph.class);
        assertThat(((CompilationResult.SingleGraph) result).graph()).isSameAs(emptyGraph);
    }

    @Test
    void single_nullGraphThrows() {
        assertThatThrownBy(() -> CompilationResult.single(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void lifecycle_requiresNonEmptyPhases() {
        assertThatThrownBy(() -> CompilationResult.lifecycle(List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lifecycle_defensiveCopy() {
        var phases = new java.util.ArrayList<>(List.of(
            new Phase("build", emptyGraph, CompletionCondition.allPresent()),
            new Phase("defend", otherGraph, CompletionCondition.never())
        ));
        CompilationResult.Lifecycle lifecycle = (CompilationResult.Lifecycle) CompilationResult.lifecycle(phases);
        phases.clear();
        assertThat(lifecycle.phases()).hasSize(2);
    }

    @Test
    void phase_nullIdThrows() {
        assertThatThrownBy(() -> new Phase(null, emptyGraph, CompletionCondition.never()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void phase_isTerminal() {
        Phase terminal = new Phase("t", emptyGraph, CompletionCondition.never());
        Phase nonTerminal = new Phase("nt", emptyGraph, CompletionCondition.allPresent());
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(nonTerminal.isTerminal()).isFalse();
    }

    @Test
    void allPresent_allNodesPresent_returnsTrue() {
        var graph = new TestGraph(Map.of(
            NodeId.of("a"), new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE),
            NodeId.of("b"), new DesiredNode(NodeId.of("b"), NodeType.of("t"), new TestSpec(), HumanGating.NONE)
        ));
        var actual = new ActualState(Map.of(
            NodeId.of("a"), NodeStatus.PRESENT,
            NodeId.of("b"), NodeStatus.PRESENT
        ));
        assertThat(CompletionCondition.allPresent().isComplete(graph, actual)).isTrue();
    }

    @Test
    void allPresent_someAbsent_returnsFalse() {
        var graph = new TestGraph(Map.of(
            NodeId.of("a"), new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE)
        ));
        var actual = new ActualState(Map.of(NodeId.of("a"), NodeStatus.ABSENT));
        assertThat(CompletionCondition.allPresent().isComplete(graph, actual)).isFalse();
    }

    @Test
    void allPresent_unknownStatus_returnsFalse() {
        var graph = new TestGraph(Map.of(
            NodeId.of("a"), new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE)
        ));
        var actual = new ActualState(Map.of());
        assertThat(CompletionCondition.allPresent().isComplete(graph, actual)).isFalse();
    }

    @Test
    void never_alwaysReturnsFalse() {
        assertThat(CompletionCondition.never().isComplete(emptyGraph, new ActualState(Map.of()))).isFalse();
    }

    @Test
    void patternMatch_exhaustive() {
        CompilationResult single = CompilationResult.single(emptyGraph);
        String type = switch (single) {
            case CompilationResult.SingleGraph sg -> "single";
            case CompilationResult.Lifecycle lc -> "lifecycle";
        };
        assertThat(type).isEqualTo("single");
    }

    // Minimal test graph — enough for CompilationResult tests
    private record TestSpec() implements NodeSpec {}

    private static class TestGraph implements DesiredStateGraph {
        private final Map<NodeId, DesiredNode> nodes;
        TestGraph() { this(Map.of()); }
        TestGraph(Map<NodeId, DesiredNode> nodes) { this.nodes = Map.copyOf(nodes); }
        @Override public Map<NodeId, DesiredNode> nodes() { return nodes; }
        @Override public java.util.Set<Dependency> dependencies() { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> dependenciesOf(NodeId node) { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> dependentsOf(NodeId node) { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> roots() { return nodes.keySet(); }
        @Override public java.util.Set<NodeId> leaves() { return nodes.keySet(); }
        @Override public int version() { return 0; }
        @Override public boolean isEmpty() { return nodes.isEmpty(); }
        @Override public DesiredStateGraph withNode(DesiredNode node) { return this; }
        @Override public DesiredStateGraph withoutNode(NodeId id) { return this; }
        @Override public DesiredStateGraph withDependency(Dependency dep) { return this; }
        @Override public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
        @Override public DesiredStateGraph withMutation(GraphMutation mutation) { return this; }
        @Override public DesiredStateGraph overlay(DesiredStateGraph other) { return this; }
        @Override public DesiredStateGraph connect(DesiredStateGraph other) { return this; }
    }
}
