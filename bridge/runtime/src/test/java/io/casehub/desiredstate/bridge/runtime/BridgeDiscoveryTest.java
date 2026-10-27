package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.NodeProvisioner;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

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
}
