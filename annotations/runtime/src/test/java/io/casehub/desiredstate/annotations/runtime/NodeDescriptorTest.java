package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.HumanGating;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeDescriptorTest {

    @Test
    void inlineNodeImplementsNodeDescriptor() {
        NodeDescriptor node = new NodeDescriptor.InlineNode(
                "test-node",
                "io.casehub.desiredstate.example.pipeline.DataSourceSpec",
                Map.of("name", "test", "format", "CSV", "uri", "s3://test"),
                HumanGating.NONE);

        assertThat(node.id()).isEqualTo("test-node");
        assertThat(node).isInstanceOf(NodeDescriptor.InlineNode.class);

        var inline = (NodeDescriptor.InlineNode) node;
        assertThat(inline.specClassName()).isEqualTo("io.casehub.desiredstate.example.pipeline.DataSourceSpec");
        assertThat(inline.specValues()).containsEntry("name", "test");
        assertThat(inline.humanGating()).isEqualTo(HumanGating.NONE);
    }

    @Test
    void sealedHierarchyPermitsInlineNode() {
        assertThat(NodeDescriptor.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .contains("InlineNode", "InterfaceNode", "ClassNode");
    }
}
