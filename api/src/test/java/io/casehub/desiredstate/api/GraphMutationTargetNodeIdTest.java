package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMutationTargetNodeIdTest {

    record TestSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("test"); }
    }

    @Test
    void addNodeTargetNodeId() {
        var node = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        var mutation = new GraphMutation.AddNode(node);
        assertThat(mutation.targetNodeId()).isEqualTo(NodeId.of("a"));
    }

    @Test
    void removeNodeTargetNodeId() {
        var mutation = new GraphMutation.RemoveNode(NodeId.of("b"));
        assertThat(mutation.targetNodeId()).isEqualTo(NodeId.of("b"));
    }

    @Test
    void updateNodeTargetNodeId() {
        var node = new DesiredNode(NodeId.of("c"), new TestSpec(), HumanGating.NONE);
        var mutation = new GraphMutation.UpdateNode(NodeId.of("c"), node);
        assertThat(mutation.targetNodeId()).isEqualTo(NodeId.of("c"));
    }

    @Test
    void addDependencyTargetNodeIdIsNull() {
        var mutation = new GraphMutation.AddDependency(new Dependency(NodeId.of("a"), NodeId.of("b")));
        assertThat(mutation.targetNodeId()).isNull();
    }

    @Test
    void removeDependencyTargetNodeIdIsNull() {
        var mutation = new GraphMutation.RemoveDependency(new Dependency(NodeId.of("a"), NodeId.of("b")));
        assertThat(mutation.targetNodeId()).isNull();
    }
}
