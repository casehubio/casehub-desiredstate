package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeSpecFactory;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeSpecRegistryFactoryTest {

    @NodeTypeId("java-type")
    public record JavaSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("java-type"); }
    }

    @Test
    void resolveFactoryForYamlType() {
        NodeSpecFactory factory = specMap -> new NodeSpec() {
            @Override
            public NodeType nodeType() { return NodeType.of("yaml-type"); }
        };
        var registry = NodeSpecRegistry.of(
            Map.of("java-type", JavaSpec.class.getName()),
            Map.of("yaml-type", factory));

        assertThat(registry.resolveFactory("yaml-type")).isPresent();
        assertThat(registry.isFactoryType("yaml-type")).isTrue();
        assertThat(registry.isFactoryType("java-type")).isFalse();
        assertThat(registry.resolve("java-type")).isEqualTo(JavaSpec.class);
    }

    @Test
    void factoryProducesNodeSpec() {
        NodeSpecFactory factory = specMap -> new NodeSpec() {
            @Override
            public NodeType nodeType() { return NodeType.of("yaml-type"); }
        };
        var registry = NodeSpecRegistry.of(Map.of(),
            Map.of("yaml-type", factory));

        var spec = registry.resolveFactory("yaml-type").orElseThrow()
            .create(Map.of("name", "test"));
        assertThat(spec.nodeType()).isEqualTo(NodeType.of("yaml-type"));
    }

    @Test
    void resolveFactoryReturnsEmptyForClassType() {
        var registry = NodeSpecRegistry.of(
            Map.of("java-type", JavaSpec.class.getName()));
        assertThat(registry.resolveFactory("java-type")).isEmpty();
    }

    @Test
    void availableTypesIncludesFactoryTypes() {
        NodeSpecFactory factory = specMap -> new NodeSpec() {
            @Override
            public NodeType nodeType() { return NodeType.of("yaml-type"); }
        };
        var registry = NodeSpecRegistry.of(
            Map.of("java-type", JavaSpec.class.getName()),
            Map.of("yaml-type", factory));
        assertThat(registry.availableTypes())
            .containsExactlyInAnyOrder("java-type", "yaml-type");
    }
}
