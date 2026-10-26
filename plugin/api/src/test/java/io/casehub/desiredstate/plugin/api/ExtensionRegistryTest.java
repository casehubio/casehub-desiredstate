package io.casehub.desiredstate.plugin.api;

import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionRegistryTest {

    private ExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExtensionRegistry();
    }

    @Test
    void emptyRegistryHasNoTypes() {
        assertThat(registry.availableTypes()).isEmpty();
        assertThat(registry.staticTypes()).isEmpty();
        assertThat(registry.dynamicTypes()).isEmpty();
    }

    @Test
    void registerStaticType() {
        registry.registerStatic(NodeType.of("k8s-pod"));
        assertThat(registry.availableTypes()).containsExactly(NodeType.of("k8s-pod"));
        assertThat(registry.staticTypes()).containsExactly(NodeType.of("k8s-pod"));
        assertThat(registry.dynamicTypes()).isEmpty();
        assertThat(registry.isTypeAvailable(NodeType.of("k8s-pod"))).isTrue();
    }

    @Test
    void registerDynamicType() {
        registry.registerDynamic(NodeType.of("monitoring-endpoint"), "manifest-placeholder");
        assertThat(registry.availableTypes()).containsExactly(NodeType.of("monitoring-endpoint"));
        assertThat(registry.dynamicTypes()).containsExactly(NodeType.of("monitoring-endpoint"));
        assertThat(registry.staticTypes()).isEmpty();
        assertThat(registry.isTypeAvailable(NodeType.of("monitoring-endpoint"))).isTrue();
    }

    @Test
    void unregisterDynamicType() {
        registry.registerDynamic(NodeType.of("monitoring-endpoint"), "manifest");
        registry.unregisterDynamic(NodeType.of("monitoring-endpoint"));
        assertThat(registry.availableTypes()).isEmpty();
        assertThat(registry.isTypeAvailable(NodeType.of("monitoring-endpoint"))).isFalse();
    }

    @Test
    void availableTypesCombinesStaticAndDynamic() {
        registry.registerStatic(NodeType.of("k8s-pod"));
        registry.registerDynamic(NodeType.of("monitoring-endpoint"), "manifest");
        assertThat(registry.availableTypes()).containsExactlyInAnyOrder(
            NodeType.of("k8s-pod"), NodeType.of("monitoring-endpoint"));
    }

    @Test
    void isTypeAvailableReturnsFalseForUnknown() {
        assertThat(registry.isTypeAvailable(NodeType.of("unknown"))).isFalse();
    }

    @Test
    void unregisterDynamicDoesNotAffectStatic() {
        registry.registerStatic(NodeType.of("k8s-pod"));
        registry.unregisterDynamic(NodeType.of("k8s-pod"));
        assertThat(registry.isTypeAvailable(NodeType.of("k8s-pod"))).isTrue();
        assertThat(registry.staticTypes()).containsExactly(NodeType.of("k8s-pod"));
    }
}
