package io.casehub.desiredstate.api;

import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SituationRecompilerTest {

    private record TestNodeSpec(String config) implements NodeSpec {}

    @Test
    void situationRecompiler_canBeImplemented() {
        SituationRecompiler recompiler = (current, situation, factory) -> Optional.empty();

        ActiveSituation situation = new ActiveSituation(
            "sit-1", "zone-A", "tenant-1", 0.95,
            Map.of("nodeId", "node-123", "reason", "persistent-drift"),
            Instant.now().minusSeconds(300), Instant.now(), 5);

        DesiredStateGraphFactory mockFactory = new DesiredStateGraphFactory() {
            @Override public DesiredStateGraph empty() { return null; }
            @Override public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) { return null; }
        };

        Optional<CompilationResult> result = recompiler.recompile(null, situation, mockFactory);

        assertThat(result).isEmpty();
    }

    @Test
    void situationRecompiler_canReturnNewGraph() {
        DesiredStateGraph mockGraph = new DesiredStateGraph() {
            @Override public Map<NodeId, DesiredNode> nodes() { return Map.of(); }
            @Override public Set<Dependency> dependencies() { return Set.of(); }
            @Override public Set<NodeId> dependenciesOf(NodeId node) { return Set.of(); }
            @Override public Set<NodeId> dependentsOf(NodeId node) { return Set.of(); }
            @Override public Set<NodeId> roots() { return Set.of(); }
            @Override public Set<NodeId> leaves() { return Set.of(); }
            @Override public int version() { return 1; }
            @Override public boolean isEmpty() { return false; }
            @Override public DesiredStateGraph withNode(DesiredNode node) { return this; }
            @Override public DesiredStateGraph withoutNode(NodeId id) { return this; }
            @Override public DesiredStateGraph withDependency(Dependency dep) { return this; }
            @Override public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
            @Override public DesiredStateGraph withMutation(GraphMutation mutation) { return this; }
            @Override public DesiredStateGraph overlay(DesiredStateGraph other) { return this; }
            @Override public DesiredStateGraph connect(DesiredStateGraph other) { return this; }
        };

        SituationRecompiler recompiler = (current, situation, factory) -> Optional.of(CompilationResult.single(mockGraph));

        ActiveSituation situation = new ActiveSituation(
            "sit-1", "zone-A", "tenant-1", 0.95,
            Map.of("nodeId", "node-123", "reason", "persistent-drift"),
            Instant.now().minusSeconds(300), Instant.now(), 5);

        Optional<CompilationResult> result = recompiler.recompile(null, situation, null);

        assertThat(result).isPresent();
        DesiredStateGraph extractedGraph = ((CompilationResult.SingleGraph) result.get()).graph();
        assertThat(extractedGraph).isSameAs(mockGraph);
    }
}
