package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import io.casehub.desiredstate.bridge.BridgeTypeDescriptor;
import io.casehub.desiredstate.bridge.DiscoveredResource;
import io.casehub.desiredstate.bridge.DiscoveryCapable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BridgeDiscoveryAdapterTest {

    private static final NodeType VM = NodeType.of("cloud:vm");

    @Test
    void discoversResourcesViaDiscoveryCapable() {
        var provider = new StubDiscoveryProvider(
            "test", Set.of("cloud:vm"),
            List.of(new DiscoveredResource("i-1", "cloud:vm",
                Map.of("name", "vm-1", "instance_type", "t3.medium", "status", "running")))
        );
        var typeDesc = new BridgeTypeDescriptor(VM,
            Map.of("running", NodeStatus.PRESENT), NodeStatus.DRIFTED,
            NodeStatus.ABSENT, Map.of(), BridgeErrorCategory.PERMANENT);
        var descriptor = new BridgeDescriptor("test", Set.of(VM),
            Map.of("cloud:vm", typeDesc), null);

        var adapter = new BridgeDiscoveryAdapter(descriptor, provider);

        assertEquals(Set.of(VM), adapter.handledTypes());

        var results = adapter.discover(Set.of(VM), "tenant-1");
        assertEquals(1, results.size());
        DiscoveredNode node = results.getFirst();
        assertEquals("i-1", node.resourceId());
        assertEquals(VM, node.type());
        assertNotNull(node.spec());
        assertEquals("vm-1", node.spec().get("name"));
    }

    @Test
    void skipsUnhandledTypes() {
        var provider = new StubDiscoveryProvider("test", Set.of("cloud:vm"), List.of());
        var typeDesc = new BridgeTypeDescriptor(VM,
            Map.of(), NodeStatus.DRIFTED, NodeStatus.ABSENT, Map.of(), BridgeErrorCategory.PERMANENT);
        var descriptor = new BridgeDescriptor("test", Set.of(VM),
            Map.of("cloud:vm", typeDesc), null);

        var adapter = new BridgeDiscoveryAdapter(descriptor, provider);
        var results = adapter.discover(Set.of(NodeType.of("cloud:storage")), "tenant-1");
        assertTrue(results.isEmpty());
    }

    @Test
    void rejectsProviderWithoutDiscoveryCapable() {
        var provider = new NonDiscoveryProvider();
        var descriptor = new BridgeDescriptor("test", Set.of(VM), Map.of(), null);
        assertThrows(IllegalArgumentException.class,
            () -> new BridgeDiscoveryAdapter(descriptor, provider));
    }

    private static class StubDiscoveryProvider implements BridgeProvider, DiscoveryCapable {
        private final String protocol;
        private final Set<String> types;
        private final List<DiscoveredResource> resources;

        StubDiscoveryProvider(String protocol, Set<String> types,
                              List<DiscoveredResource> resources) {
            this.protocol = protocol;
            this.types = types;
            this.resources = resources;
        }

        @Override public String protocolId() { return protocol; }
        @Override public Set<String> supportedTypes() { return types; }
        @Override public BridgeResult apply(BridgeRequest r) { return null; }
        @Override public BridgeResult destroy(BridgeRequest r) { return null; }
        @Override public BridgeStateResult readState(BridgeReadRequest r) { return null; }
        @Override
        public List<DiscoveredResource> discover(String typeId, String tenancyId) {
            return resources.stream()
                .filter(r -> r.typeId().equals(typeId))
                .toList();
        }
    }

    private static class NonDiscoveryProvider implements BridgeProvider {
        @Override public String protocolId() { return "none"; }
        @Override public Set<String> supportedTypes() { return Set.of(); }
        @Override public BridgeResult apply(BridgeRequest r) { return null; }
        @Override public BridgeResult destroy(BridgeRequest r) { return null; }
        @Override public BridgeStateResult readState(BridgeReadRequest r) { return null; }
    }
}
