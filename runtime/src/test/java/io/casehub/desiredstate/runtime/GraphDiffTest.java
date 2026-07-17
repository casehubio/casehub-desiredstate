package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphDiffTest {

    private record TestSpec(String value) implements NodeSpec {}

    private DesiredStateGraph graph(DesiredNode... nodes) {
        DesiredStateGraph g = ImmutableDesiredStateGraph.empty();
        for (DesiredNode n : nodes) {
            g = g.withNode(n);
        }
        return g;
    }

    private DesiredNode node(String id, String spec) {
        return new DesiredNode(NodeId.of(id), NodeType.of("test"), new TestSpec(spec), HumanGating.NONE);
    }

    private DesiredNode node(String id, String type, String spec) {
        return new DesiredNode(NodeId.of(id), NodeType.of(type), new TestSpec(spec), HumanGating.NONE);
    }

    @Test
    void emptyBothGraphs_shouldReturnNoMutations() {
        List<GraphMutation> mutations = GraphDiff.computeMutations(
            ImmutableDesiredStateGraph.empty(), ImmutableDesiredStateGraph.empty());
        assertThat(mutations).isEmpty();
    }

    @Test
    void newNodeInAdapted_shouldProduceAddNode() {
        DesiredStateGraph current = ImmutableDesiredStateGraph.empty();
        DesiredStateGraph adapted = graph(node("n1", "v1"));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        assertThat(((GraphMutation.AddNode) mutations.get(0)).node().id()).isEqualTo(NodeId.of("n1"));
    }

    @Test
    void changedSpecInAdapted_shouldProduceUpdateNode() {
        DesiredStateGraph current = graph(node("n1", "v1"));
        DesiredStateGraph adapted = graph(node("n1", "v2"));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.UpdateNode.class);
        GraphMutation.UpdateNode update = (GraphMutation.UpdateNode) mutations.get(0);
        assertThat(update.id()).isEqualTo(NodeId.of("n1"));
        assertThat(update.adaptedNode().spec()).isEqualTo(new TestSpec("v2"));
    }

    @Test
    void unchangedNode_shouldProduceNoMutations() {
        DesiredStateGraph current = graph(node("n1", "v1"));
        DesiredStateGraph adapted = graph(node("n1", "v1"));

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).isEmpty();
    }

    @Test
    void nodeInCurrentButOutOfScope_shouldNotBeRemoved() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n3 = node("n3", "other", "v3");

        DesiredStateGraph current = graph(n1, n3);
        DesiredStateGraph adapted = graph(n1);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).isEmpty();
    }

    @Test
    void nodeInCurrentSameTypeAsAdapted_butNotInAdapted_shouldBeRemoved() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "test", "v2");
        DesiredNode n3 = node("n3", "other", "v3");

        DesiredStateGraph current = graph(n1, n2, n3);
        DesiredStateGraph adapted = graph(n1);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.RemoveNode.class);
        assertThat(((GraphMutation.RemoveNode) mutations.get(0)).id()).isEqualTo(NodeId.of("n2"));
    }

    @Test
    void addDependency_newInAdapted() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "test", "v2");
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n1, n2);
        DesiredStateGraph adapted = graph(n1, n2).withDependency(dep);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddDependency.class);
        assertThat(((GraphMutation.AddDependency) mutations.get(0)).dependency()).isEqualTo(dep);
    }

    @Test
    void addDependency_crossBoundaryToExistingNode() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "other", "v2");
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n2);
        DesiredStateGraph adapted = graph(n1, n2).withDependency(dep);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.AddNode).hasSize(1);
        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.AddDependency).hasSize(1);
    }

    @Test
    void removeDependency_betweenInScopeNodes() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "test", "v2");
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n1, n2).withDependency(dep);
        DesiredStateGraph adapted = graph(n1, n2);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.RemoveDependency.class);
    }

    @Test
    void crossBoundaryDependency_shouldNotBeRemoved() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "other", "v2");
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n1, n2).withDependency(dep);
        DesiredStateGraph adapted = graph(n1);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.RemoveDependency).isEmpty();
    }

    @Test
    void multipleChanges_shouldProduceCorrectMutations() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "test", "v2");
        DesiredNode n3 = node("n3", "test", "v3");

        DesiredStateGraph current = graph(n1, n2);
        DesiredStateGraph adapted = graph(node("n1", "test", "v1-updated"), n3);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.UpdateNode).hasSize(1);
        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.AddNode).hasSize(1);
        assertThat(mutations).filteredOn(m -> m instanceof GraphMutation.RemoveNode).hasSize(1);
    }

    @Test
    void existingDependencyInBothGraphs_shouldNotBeDuplicated() {
        DesiredNode n1 = node("n1", "test", "v1");
        DesiredNode n2 = node("n2", "test", "v2");
        Dependency dep = new Dependency(NodeId.of("n1"), NodeId.of("n2"));

        DesiredStateGraph current = graph(n1, n2).withDependency(dep);
        DesiredStateGraph adapted = graph(n1, n2).withDependency(dep);

        List<GraphMutation> mutations = GraphDiff.computeMutations(current, adapted);

        assertThat(mutations).isEmpty();
    }

    @Test
    void targetNodeId_addNode() {
        DesiredNode n = node("n1", "v1");
        assertThat(GraphDiff.targetNodeId(new GraphMutation.AddNode(n))).isEqualTo(NodeId.of("n1"));
    }

    @Test
    void targetNodeId_removeNode() {
        assertThat(GraphDiff.targetNodeId(new GraphMutation.RemoveNode(NodeId.of("n1")))).isEqualTo(NodeId.of("n1"));
    }

    @Test
    void targetNodeId_updateNode() {
        assertThat(GraphDiff.targetNodeId(new GraphMutation.UpdateNode(NodeId.of("n1"), node("n1", "v2")))).isEqualTo(NodeId.of("n1"));
    }

    @Test
    void targetNodeId_addDependency_returnsNull() {
        assertThat(GraphDiff.targetNodeId(new GraphMutation.AddDependency(
                new Dependency(NodeId.of("a"), NodeId.of("b"))))).isNull();
    }

    @Test
    void targetNodeId_removeDependency_returnsNull() {
        assertThat(GraphDiff.targetNodeId(new GraphMutation.RemoveDependency(
                new Dependency(NodeId.of("a"), NodeId.of("b"))))).isNull();
    }

    @Test
    void humanGating_change_only_generates_updateNode() {
        DesiredNode current = new DesiredNode(NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredNode adapted = new DesiredNode(NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.DEPROVISION_ONLY);

        List<GraphMutation> mutations = GraphDiff.computeMutations(graph(current), graph(adapted));

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.UpdateNode.class);
        GraphMutation.UpdateNode update = (GraphMutation.UpdateNode) mutations.get(0);
        assertThat(update.id()).isEqualTo(NodeId.of("n1"));
    }

}
