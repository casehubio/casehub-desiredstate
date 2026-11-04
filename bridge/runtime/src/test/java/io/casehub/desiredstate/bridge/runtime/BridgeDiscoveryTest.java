package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DiscoveryAdapter;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.bridge.DiscoveredResource;
import io.casehub.desiredstate.bridge.DiscoveryCapable;
import io.casehub.desiredstate.bridge.testing.MockBridgeProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeDiscoveryTest {

    @Test
    void discoverAllReturnsProvisionerAndAdapterBeans() {
        var provider = new MockBridgeProvider("test-provider", "test_resource", "test_bucket");
        var descriptor = BridgeManifestParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));

        var beans = BridgeDiscovery.discoverAll(List.of(descriptor), List.of(provider));

        assertEquals(2, beans.size());
        assertTrue(beans.stream().anyMatch(b -> b.type() == NodeProvisioner.class));
        assertTrue(beans.stream().anyMatch(b -> b.type() == ActualStateAdapter.class));
    }

    @Test
    void skipsDescriptorWithNoMatchingProvider() {
        var descriptor = BridgeManifestParser.parse(
            getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));

        var beans = BridgeDiscovery.discoverAll(List.of(descriptor), List.of());
        assertTrue(beans.isEmpty());
    }

    @Test
    void registersDiscoveryAdapterWhenProviderIsDiscoveryCapable() {
        var provider = new DiscoveryMockBridgeProvider("test-provider", "test_resource");
        var descriptor = BridgeManifestParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));

        var beans = BridgeDiscovery.discoverAll(List.of(descriptor), List.of(provider));

        assertEquals(3, beans.size());
        assertTrue(beans.stream().anyMatch(b -> b.type() == DiscoveryAdapter.class));
    }

    @Test
    void doesNotRegisterDiscoveryAdapterWhenProviderLacksCapability() {
        var provider = new MockBridgeProvider("test-provider", "test_resource", "test_bucket");
        var descriptor = BridgeManifestParser.parse(
                getClass().getResourceAsStream("/META-INF/desiredstate/bridges/test-bridge.yaml"));

        var beans = BridgeDiscovery.discoverAll(List.of(descriptor), List.of(provider));

        assertEquals(2, beans.size());
        assertFalse(beans.stream().anyMatch(b -> b.type() == DiscoveryAdapter.class));
    }

    private static class DiscoveryMockBridgeProvider extends MockBridgeProvider
            implements DiscoveryCapable {
        DiscoveryMockBridgeProvider(String protocol, String... types) {
            super(protocol, types);
        }

        @Override
        public List<DiscoveredResource> discover(String typeId, String tenancyId) {
            return List.of();
        }
    }

}
