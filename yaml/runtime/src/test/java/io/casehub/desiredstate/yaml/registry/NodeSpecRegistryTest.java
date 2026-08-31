package io.casehub.desiredstate.yaml.registry;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeSpecFactory;
import io.casehub.desiredstate.api.NodeSpecFactoryProvider;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeSpecRegistryTest {

    @NodeTypeId("test-type")
    public record TestNodeSpec(String name) implements NodeSpec {
        public TestNodeSpec {if (name == null) {name = "default";}}

        @Override
        public NodeType nodeType() {return NodeType.of("test-type");}
    }

    @NodeTypeId("other-type")
    public record OtherNodeSpec() implements NodeSpec {
        @Override
        public NodeType nodeType() {return NodeType.of("other-type");}
    }

    @Test
    void resolve_returnsFactoryThatCreatesNodeSpec() {
        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()));
        NodeSpecFactory factory = registry.resolve("test-type");
        NodeSpec        spec    = factory.create(Map.of("name", "hello"));
        assertThat(spec).isInstanceOf(TestNodeSpec.class);
        assertThat(((TestNodeSpec) spec).name()).isEqualTo("hello");
    }

    @Test
    void resolve_directCastFactoryCoalescesNulls() {
        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()));
        NodeSpecFactory factory = registry.resolve("test-type");
        NodeSpec        spec    = factory.create(Map.of());
        assertThat(((TestNodeSpec) spec).name()).isEqualTo("default");
    }

    @Test
    void resolve_throwsOnUnknownType() {
        var registry = NodeSpecRegistry.of(Map.of("test-type", TestNodeSpec.class.getName()));
        assertThatThrownBy(() -> registry.resolve("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("test-type");
    }

    @Test
    void availableTypes_returnsAllRegisteredTypes() {
        var registry = NodeSpecRegistry.of(Map.of(
                "type-a", TestNodeSpec.class.getName(),
                "type-b", OtherNodeSpec.class.getName()));
        assertThat(registry.availableTypes()).containsExactlyInAnyOrder("type-a", "type-b");
    }

    @Test
    void resolveByClassName_returnsFactoryForClassName() {
        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()));
        NodeSpecFactory factory = registry.resolveByClassName(TestNodeSpec.class.getName());
        NodeSpec        spec    = factory.create(Map.of("name", "via-classname"));
        assertThat(((TestNodeSpec) spec).name()).isEqualTo("via-classname");
    }

    @Test
    void resolveByClassName_throwsOnUnknownClassName() {
        var registry = NodeSpecRegistry.of(Map.of("test-type", TestNodeSpec.class.getName()));
        assertThatThrownBy(() -> registry.resolveByClassName("com.nonexistent.Spec"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.nonexistent.Spec");
    }

    @Test
    void of_providerFactoriesTakePriority() {
        NodeSpecFactory         customFactory = specMap -> new OtherNodeSpec();
        NodeSpecFactoryProvider provider      = () -> Map.of("test-type", customFactory);

        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()),
                List.of(provider));

        NodeSpec spec = registry.resolve("test-type").create(Map.of());
        assertThat(spec).isInstanceOf(OtherNodeSpec.class);
    }

    @Test
    void of_providerAddsTypesNotInScan() {
        NodeSpecFactory         customFactory = specMap -> new OtherNodeSpec();
        NodeSpecFactoryProvider provider      = () -> Map.of("custom-type", customFactory);

        var registry = NodeSpecRegistry.of(
                Map.of("test-type", TestNodeSpec.class.getName()),
                List.of(provider));

        assertThat(registry.availableTypes()).containsExactlyInAnyOrder("test-type", "custom-type");
        assertThat(registry.resolve("custom-type").create(Map.of())).isInstanceOf(OtherNodeSpec.class);
    }

    @Test
    void yamlNode_backendIdAvailable() {
        var node = new io.casehub.desiredstate.yaml.model.YamlNode(
                "k8s-deployment", Map.of("name", "web"), List.of(), null, null, null, null, null, "aws-eks");
        assertThat(node.backendId()).isEqualTo("aws-eks");
    }

    @Test
    void yamlNode_backendIdDefaultsToNull() {
        var node = new io.casehub.desiredstate.yaml.model.YamlNode(
                "k8s-deployment", Map.of("name", "web"), List.of(), null, null, null, null, null);
        assertThat(node.backendId()).isNull();
    }
}
