package io.casehub.desiredstate.yaml.registry;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeSpecFactory;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeSpecRegistryTest {

    @NodeTypeId("test-type")
    public record TestNodeSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("test-type"); }
    }

    @Test
    void resolvesKnownType() {
        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()));
        assertThat(registry.resolve("test-type")).isEqualTo(TestNodeSpec.class);
    }

    @Test
    void throwsOnUnknownType() {
        var registry = NodeSpecRegistry.of(Map.of("test-type", TestNodeSpec.class.getName()));
        assertThatThrownBy(() -> registry.resolve("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("test-type");
    }

    @Test
    void reportsAvailableTypes() {
        var registry = NodeSpecRegistry.of(Map.of(
                "type-a", TestNodeSpec.class.getName(),
                "type-b", TestNodeSpec.class.getName()));
        assertThat(registry.availableTypes()).isEqualTo(Set.of("type-a", "type-b"));
    }

    @Test
    void resolvesByClassName() {
        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()));
        assertThat(registry.resolveByClassName(TestNodeSpec.class.getName()))
                .isEqualTo(TestNodeSpec.class);
    }

    @Test
    void throwsOnUnknownClassName() {
        var registry = NodeSpecRegistry.of(Map.of("test-type", TestNodeSpec.class.getName()));
        assertThatThrownBy(() -> registry.resolveByClassName("com.nonexistent.Spec"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.nonexistent.Spec");
    }

    @Test
    void resolveFactoryForYamlType() {
        NodeSpecFactory factory = specMap -> new NodeSpec() {
            @Override
            public NodeType nodeType() { return NodeType.of("yaml-type"); }
        };
        var registry = NodeSpecRegistry.of(
                Map.of("java-type", TestNodeSpec.class.getName()),
                Map.of("yaml-type", factory));
        assertThat(registry.resolveFactory("yaml-type")).isPresent();
        assertThat(registry.isFactoryType("yaml-type")).isTrue();
        assertThat(registry.isFactoryType("java-type")).isFalse();
        assertThat(registry.resolve("java-type")).isEqualTo(TestNodeSpec.class);
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
                Map.of("java-type", TestNodeSpec.class.getName()));
        assertThat(registry.resolveFactory("java-type")).isEmpty();
    }

    @Test
    void availableTypesIncludesFactoryTypes() {
        NodeSpecFactory factory = specMap -> new NodeSpec() {
            @Override
            public NodeType nodeType() { return NodeType.of("yaml-type"); }
        };
        var registry = NodeSpecRegistry.of(
                Map.of("java-type", TestNodeSpec.class.getName()),
                Map.of("yaml-type", factory));
        assertThat(registry.availableTypes())
                .containsExactlyInAnyOrder("java-type", "yaml-type");
    }
}
