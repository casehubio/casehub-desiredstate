package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMutationsTest {

    private static final NodeType REVIEW = NodeType.of("review");

    record TestSpec(String detail) implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

    @Test
    void addNodeDependingOn_returnsAddNodeAndAddDependency() {
        DesiredNode node = new DesiredNode(NodeId.of("review-n1"), new TestSpec("test"), HumanGating.NONE);
        NodeId dependsOn = NodeId.of("n1");

        List<GraphMutation> mutations = GraphMutations.addNodeDependingOn(node, dependsOn);

        assertThat(mutations).hasSize(2);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        GraphMutation.AddNode addNode = (GraphMutation.AddNode) mutations.get(0);
        assertThat(addNode.node()).isEqualTo(node);

        assertThat(mutations.get(1)).isInstanceOf(GraphMutation.AddDependency.class);
        GraphMutation.AddDependency addDep = (GraphMutation.AddDependency) mutations.get(1);
        assertThat(addDep.dependency().from()).isEqualTo(NodeId.of("review-n1"));
        assertThat(addDep.dependency().to()).isEqualTo(NodeId.of("n1"));
    }
}
