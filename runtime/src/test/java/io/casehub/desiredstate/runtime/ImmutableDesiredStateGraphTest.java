package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.CyclicDependencyException;
import io.casehub.desiredstate.api.DanglingDependencyException;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImmutableDesiredStateGraphTest {

    record TestSpec(String name) implements NodeSpec {}

    static final NodeType ROOM = NodeType.of("room");
    static final NodeType CREATURE = NodeType.of("creature");
    static final NodeType ITEM = NodeType.of("item");

    static DesiredNode node(String id) {
        return new DesiredNode(NodeId.of(id), ROOM, new TestSpec(id), HumanGating.NONE);
    }

    static DesiredNode node(String id, NodeType type) {
        return new DesiredNode(NodeId.of(id), type, new TestSpec(id), HumanGating.NONE);
    }

    static DesiredNode node(String id, NodeSpec spec) {
        return new DesiredNode(NodeId.of(id), ROOM, spec, HumanGating.NONE);
    }

    static Dependency dep(String from, String to) {
        return new Dependency(NodeId.of(from), NodeId.of(to));
    }

    // --- Factory ---

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    // === 1. Empty graph ===

    @Test void empty_graph_is_empty() {
        var g = factory.empty();
        assertThat(g.isEmpty()).isTrue();
        assertThat(g.version()).isEqualTo(0);
        assertThat(g.nodes()).isEmpty();
        assertThat(g.dependencies()).isEmpty();
        assertThat(g.roots()).isEmpty();
        assertThat(g.leaves()).isEmpty();
    }

    // === 2. Add/remove node ===

    @Test void withNode_returns_new_graph_with_node() {
        var g0 = factory.empty();
        var g1 = g0.withNode(node("A"));

        assertThat(g1.nodes()).containsKey(NodeId.of("A"));
        assertThat(g1.isEmpty()).isFalse();
        assertThat(g1.version()).isEqualTo(1);

        // original is unchanged
        assertThat(g0.isEmpty()).isTrue();
        assertThat(g0.version()).isEqualTo(0);
    }

    @Test void withNode_replaces_existing_node_same_id() {
        var specA = new TestSpec("original");
        var specB = new TestSpec("replacement");
        var g = factory.empty()
                .withNode(node("A", specA))
                .withNode(node("A", specB));

        assertThat(g.nodes().get(NodeId.of("A")).spec()).isEqualTo(specB);
    }

    @Test void withoutNode_removes_node() {
        var g = factory.empty()
                .withNode(node("A"))
                .withoutNode(NodeId.of("A"));

        assertThat(g.isEmpty()).isTrue();
        assertThat(g.version()).isEqualTo(2);
    }

    @Test void withoutNode_nonexistent_returns_same_version() {
        var g = factory.empty();
        var g2 = g.withoutNode(NodeId.of("phantom"));
        // removing a non-existent node still returns a new instance with bumped version
        // (mutation is always versioned, even if it's a no-op semantically)
        assertThat(g2.version()).isEqualTo(1);
    }

    // === 3. Remove node also removes edges ===

    @Test void withoutNode_removes_all_edges_referencing_node() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withDependency(dep("B", "A"))    // B depends on A
                .withDependency(dep("C", "B"));   // C depends on B

        var g2 = g.withoutNode(NodeId.of("B"));

        assertThat(g2.nodes()).doesNotContainKey(NodeId.of("B"));
        assertThat(g2.dependencies()).isEmpty();
        assertThat(g2.dependenciesOf(NodeId.of("C"))).isEmpty();
        assertThat(g2.dependentsOf(NodeId.of("A"))).isEmpty();
    }

    // === 4. Add/remove dependency ===

    @Test void withDependency_adds_edge() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"));

        assertThat(g.dependencies()).containsExactly(dep("B", "A"));
        assertThat(g.dependenciesOf(NodeId.of("B"))).containsExactly(NodeId.of("A"));
        assertThat(g.dependentsOf(NodeId.of("A"))).containsExactly(NodeId.of("B"));
    }

    @Test void withoutDependency_removes_edge() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"))
                .withoutDependency(dep("B", "A"));

        assertThat(g.dependencies()).isEmpty();
        assertThat(g.dependenciesOf(NodeId.of("B"))).isEmpty();
        assertThat(g.dependentsOf(NodeId.of("A"))).isEmpty();
    }

    // === 5. Dangling dependency detection ===

    @Test void withDependency_throws_when_from_node_missing() {
        var g = factory.empty().withNode(node("A"));

        assertThatThrownBy(() -> g.withDependency(dep("phantom", "A")))
                .isInstanceOf(DanglingDependencyException.class);
    }

    @Test void withDependency_throws_when_to_node_missing() {
        var g = factory.empty().withNode(node("A"));

        assertThatThrownBy(() -> g.withDependency(dep("A", "phantom")))
                .isInstanceOf(DanglingDependencyException.class);
    }

    @Test void withDependency_throws_when_both_nodes_missing() {
        var g = factory.empty();

        assertThatThrownBy(() -> g.withDependency(dep("X", "Y")))
                .isInstanceOf(DanglingDependencyException.class);
    }

    // === 6. Self-loop cycle detection ===

    @Test void withDependency_self_loop_throws() {
        var g = factory.empty().withNode(node("A"));

        assertThatThrownBy(() -> g.withDependency(dep("A", "A")))
                .isInstanceOf(CyclicDependencyException.class)
                .satisfies(ex -> {
                    var cycle = ((CyclicDependencyException) ex).getCycle();
                    assertThat(cycle).contains(NodeId.of("A"));
                });
    }

    // === 7. Two-node cycle detection ===

    @Test void withDependency_two_node_cycle_throws() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"));   // B depends on A

        // Now try A depends on B — creates cycle A→B→A
        assertThatThrownBy(() -> g.withDependency(dep("A", "B")))
                .isInstanceOf(CyclicDependencyException.class);
    }

    // === 8. Transitive cycle detection ===

    @Test void withDependency_transitive_cycle_throws() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withDependency(dep("B", "A"))    // B depends on A
                .withDependency(dep("C", "B"));   // C depends on B

        // A depends on C — creates cycle A→C→B→A
        assertThatThrownBy(() -> g.withDependency(dep("A", "C")))
                .isInstanceOf(CyclicDependencyException.class)
                .satisfies(ex -> {
                    var cycle = ((CyclicDependencyException) ex).getCycle();
                    assertThat(cycle).hasSizeGreaterThanOrEqualTo(3);
                });
    }

    // === 9. Diamond pattern is NOT a cycle ===

    @Test void diamond_pattern_is_valid() {
        // A→B, A→C, B→D, C→D  (diamond, not a cycle)
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withNode(node("D"))
                .withDependency(dep("A", "B"))
                .withDependency(dep("A", "C"))
                .withDependency(dep("B", "D"))
                .withDependency(dep("C", "D"));

        assertThat(g.dependencies()).hasSize(4);
        assertThat(g.roots()).containsExactly(NodeId.of("D"));
        assertThat(g.leaves()).containsExactly(NodeId.of("A"));
    }

    // === 10. Roots and leaves queries ===

    @Test void roots_are_nodes_with_no_dependencies() {
        // chain: C → B → A
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withDependency(dep("B", "A"))
                .withDependency(dep("C", "B"));

        assertThat(g.roots()).containsExactly(NodeId.of("A"));
    }

    @Test void leaves_are_nodes_with_no_dependents() {
        // chain: C → B → A
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withDependency(dep("B", "A"))
                .withDependency(dep("C", "B"));

        assertThat(g.leaves()).containsExactly(NodeId.of("C"));
    }

    @Test void isolated_node_is_both_root_and_leaf() {
        var g = factory.empty().withNode(node("lonely"));

        assertThat(g.roots()).containsExactly(NodeId.of("lonely"));
        assertThat(g.leaves()).containsExactly(NodeId.of("lonely"));
    }

    @Test void multiple_roots_and_leaves() {
        // A, B are independent roots. C depends on both. D depends on C.
        // Roots: A, B.  Leaves: D.
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withNode(node("D"))
                .withDependency(dep("C", "A"))
                .withDependency(dep("C", "B"))
                .withDependency(dep("D", "C"));

        assertThat(g.roots()).containsExactlyInAnyOrder(NodeId.of("A"), NodeId.of("B"));
        assertThat(g.leaves()).containsExactly(NodeId.of("D"));
    }

    // === 11. Overlay composition ===

    @Test void overlay_merges_non_overlapping_graphs() {
        var g1 = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"));

        var g2 = factory.empty()
                .withNode(node("C"))
                .withNode(node("D"))
                .withDependency(dep("D", "C"));

        var merged = g1.overlay(g2);

        assertThat(merged.nodes()).hasSize(4);
        assertThat(merged.dependencies()).hasSize(2);
    }

    @Test void overlay_shared_nodes_with_equal_specs_succeeds() {
        var spec = new TestSpec("shared");
        var g1 = factory.empty().withNode(node("A", spec));
        var g2 = factory.empty().withNode(node("A", spec));

        var merged = g1.overlay(g2);
        assertThat(merged.nodes()).hasSize(1);
    }

    @Test void overlay_shared_nodes_with_different_specs_throws() {
        var g1 = factory.empty().withNode(node("A", new TestSpec("v1")));
        var g2 = factory.empty().withNode(node("A", new TestSpec("v2")));

        assertThatThrownBy(() -> g1.overlay(g2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A");
    }

    @Test void overlay_merges_dependencies_from_both_graphs() {
        var spec = new TestSpec("shared");
        var g1 = factory.empty()
                .withNode(node("A", spec))
                .withNode(node("B"))
                .withDependency(dep("B", "A"));

        var g2 = factory.empty()
                .withNode(node("A", spec))
                .withNode(node("C"))
                .withDependency(dep("C", "A"));

        var merged = g1.overlay(g2);
        assertThat(merged.dependencies()).hasSize(2);
        assertThat(merged.dependentsOf(NodeId.of("A")))
                .containsExactlyInAnyOrder(NodeId.of("B"), NodeId.of("C"));
    }

    // === 12. Connect composition ===

    @Test void connect_links_leaves_of_this_to_roots_of_other() {
        // g1: A → B (leaf = A)
        // g2: C → D (root = D)
        // connect: should add edge A → D
        var g1 = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("A", "B"));

        var g2 = factory.empty()
                .withNode(node("C"))
                .withNode(node("D"))
                .withDependency(dep("C", "D"));

        var connected = g1.connect(g2);

        assertThat(connected.nodes()).hasSize(4);
        // A was leaf of g1, D was root of g2 → new edge A→D
        assertThat(connected.dependenciesOf(NodeId.of("A")))
                .containsExactlyInAnyOrder(NodeId.of("B"), NodeId.of("D"));
    }

    @Test void connect_multiple_leaves_to_multiple_roots() {
        // g1 has leaves: A and B (no deps)
        // g2 has roots: C and D (no dependents, but each has deps — or just standalone)
        var g1 = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"));

        var g2 = factory.empty()
                .withNode(node("C"))
                .withNode(node("D"));

        var connected = g1.connect(g2);

        // Leaves of g1 (A, B) should now depend on roots of g2 (C, D)
        assertThat(connected.dependenciesOf(NodeId.of("A")))
                .containsExactlyInAnyOrder(NodeId.of("C"), NodeId.of("D"));
        assertThat(connected.dependenciesOf(NodeId.of("B")))
                .containsExactlyInAnyOrder(NodeId.of("C"), NodeId.of("D"));
    }

    // === 13. withMutation for each GraphMutation variant ===

    @Test void withMutation_addNode() {
        var g = factory.empty()
                .withMutation(new GraphMutation.AddNode(node("A")));

        assertThat(g.nodes()).containsKey(NodeId.of("A"));
    }

    @Test void withMutation_removeNode() {
        var g = factory.empty()
                .withNode(node("A"))
                .withMutation(new GraphMutation.RemoveNode(NodeId.of("A")));

        assertThat(g.isEmpty()).isTrue();
    }

    @Test void withMutation_updateNode() {
        var newSpec = new TestSpec("updated");
        var g = factory.empty()
                .withNode(node("A"))
                .withMutation(new GraphMutation.UpdateNode(NodeId.of("A"), new DesiredNode(NodeId.of("A"), NodeType.of("test"), newSpec, HumanGating.NONE)));

        assertThat(g.nodes().get(NodeId.of("A")).spec()).isEqualTo(newSpec);
    }

    @Test void withMutation_updateNode_nonexistent_throws() {
        var g = factory.empty();

        assertThatThrownBy(() -> g.withMutation(new GraphMutation.UpdateNode(NodeId.of("phantom"), new DesiredNode(NodeId.of("phantom"), NodeType.of("test"), new TestSpec("x"), HumanGating.NONE))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void withMutation_addDependency() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withMutation(new GraphMutation.AddDependency(dep("B", "A")));

        assertThat(g.dependencies()).containsExactly(dep("B", "A"));
    }

    @Test void withMutation_removeDependency() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"))
                .withMutation(new GraphMutation.RemoveDependency(dep("B", "A")));

        assertThat(g.dependencies()).isEmpty();
    }

    // === 14. Factory.of() ===

    @Test void factory_of_builds_graph_with_nodes_and_deps() {
        var nodes = List.of(node("A"), node("B"), node("C"));
        var deps = List.of(dep("B", "A"), dep("C", "B"));

        var g = factory.of(nodes, deps);

        assertThat(g.nodes()).hasSize(3);
        assertThat(g.dependencies()).hasSize(2);
        assertThat(g.roots()).containsExactly(NodeId.of("A"));
        assertThat(g.leaves()).containsExactly(NodeId.of("C"));
    }

    @Test void factory_of_detects_cycle() {
        var nodes = List.of(node("A"), node("B"));
        var deps = List.of(dep("A", "B"), dep("B", "A"));

        assertThatThrownBy(() -> factory.of(nodes, deps))
                .isInstanceOf(CyclicDependencyException.class);
    }

    @Test void factory_of_detects_dangling() {
        var nodes = List.of(node("A"));
        var deps = List.of(dep("A", "missing"));

        assertThatThrownBy(() -> factory.of(nodes, deps))
                .isInstanceOf(DanglingDependencyException.class);
    }

    // === Additional edge cases ===

    @Test void version_increments_on_every_mutation() {
        var g = factory.empty();
        assertThat(g.version()).isEqualTo(0);

        g = g.withNode(node("A"));
        assertThat(g.version()).isEqualTo(1);

        g = g.withNode(node("B"));
        assertThat(g.version()).isEqualTo(2);

        g = g.withDependency(dep("B", "A"));
        assertThat(g.version()).isEqualTo(3);

        g = g.withoutDependency(dep("B", "A"));
        assertThat(g.version()).isEqualTo(4);

        g = g.withoutNode(NodeId.of("A"));
        assertThat(g.version()).isEqualTo(5);
    }

    @Test void dependencies_returns_all_edges_as_dependency_objects() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withNode(node("C"))
                .withDependency(dep("B", "A"))
                .withDependency(dep("C", "A"));

        assertThat(g.dependencies()).containsExactlyInAnyOrder(
                dep("B", "A"), dep("C", "A"));
    }

    @Test void dependenciesOf_unknown_node_returns_empty() {
        var g = factory.empty();
        assertThat(g.dependenciesOf(NodeId.of("phantom"))).isEmpty();
    }

    @Test void dependentsOf_unknown_node_returns_empty() {
        var g = factory.empty();
        assertThat(g.dependentsOf(NodeId.of("phantom"))).isEmpty();
    }

    @Test void immutability_returned_collections_are_unmodifiable() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"));

        assertThatThrownBy(() -> g.nodes().put(NodeId.of("X"), node("X")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.dependencies().add(dep("A", "B")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.roots().add(NodeId.of("X")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.leaves().add(NodeId.of("X")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.dependenciesOf(NodeId.of("B")).add(NodeId.of("X")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> g.dependentsOf(NodeId.of("A")).add(NodeId.of("X")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void duplicate_dependency_is_idempotent() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"))
                .withDependency(dep("B", "A"))
                .withDependency(dep("B", "A"));

        assertThat(g.dependencies()).hasSize(1);
    }

    @Test void withoutDependency_nonexistent_still_bumps_version() {
        var g = factory.empty()
                .withNode(node("A"))
                .withNode(node("B"));
        int v = g.version();
        var g2 = g.withoutDependency(dep("A", "B"));
        assertThat(g2.version()).isEqualTo(v + 1);
    }

    @Test void filterByTypes_retainsMatchingNodesAndInternalDeps() {
        var graph = ImmutableDesiredStateGraph.empty()
            .withNode(node("n1", ROOM))
            .withNode(node("n2", ROOM))
            .withNode(node("n3", CREATURE))
            .withDependency(new Dependency(NodeId.of("n2"), NodeId.of("n1")))
            .withDependency(new Dependency(NodeId.of("n3"), NodeId.of("n1")));

        var filtered = graph.filterByTypes(Set.of(ROOM));

        assertThat(filtered.nodes()).containsOnlyKeys(NodeId.of("n1"), NodeId.of("n2"));
        assertThat(filtered.dependencies()).containsExactly(
            new Dependency(NodeId.of("n2"), NodeId.of("n1")));
    }

    @Test void filterByTypes_emptySetProducesEmptyGraph() {
        var graph = ImmutableDesiredStateGraph.empty().withNode(node("n1", ROOM));

        var filtered = graph.filterByTypes(Set.of());
        assertThat(filtered.isEmpty()).isTrue();
    }

    @Test void filterByTypes_allTypesRetainsFullGraph() {
        var graph = ImmutableDesiredStateGraph.empty()
            .withNode(node("n1", ROOM))
            .withNode(node("n2", ROOM))
            .withDependency(new Dependency(NodeId.of("n2"), NodeId.of("n1")));

        var filtered = graph.filterByTypes(Set.of(ROOM));
        assertThat(filtered.nodes()).hasSize(2);
        assertThat(filtered.dependencies()).hasSize(1);
    }
}
