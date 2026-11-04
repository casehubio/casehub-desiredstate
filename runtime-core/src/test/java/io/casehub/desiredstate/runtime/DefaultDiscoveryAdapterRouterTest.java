package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.DiscoveryAdapter;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultDiscoveryAdapterRouterTest {

    private static final NodeType VM = NodeType.of("cloud:vm");
    private static final NodeType STORAGE = NodeType.of("cloud:storage");

    @Test
    void routesDiscoveryToCorrectAdapter() {
        DiscoveryAdapter vmAdapter = new StubDiscoveryAdapter(
            Set.of(VM),
            List.of(new DiscoveredNode("i-1", VM, Map.of("status", "running"), Map.of("name", "vm-1")))
        );
        DiscoveryAdapter storageAdapter = new StubDiscoveryAdapter(
            Set.of(STORAGE),
            List.of(new DiscoveredNode("s-1", STORAGE, Map.of("status", "available"), Map.of("name", "bucket-1")))
        );

        var router = new DefaultDiscoveryAdapterRouter(List.of(vmAdapter, storageAdapter));
        var results = router.discover(Set.of(VM, STORAGE), "tenant-1");

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(n -> n.resourceId().equals("i-1")));
        assertTrue(results.stream().anyMatch(n -> n.resourceId().equals("s-1")));
    }

    @Test
    void allHandledTypesReturnsAllTypes() {
        DiscoveryAdapter adapter = new StubDiscoveryAdapter(
            Set.of(VM, STORAGE), List.of()
        );
        var router = new DefaultDiscoveryAdapterRouter(List.of(adapter));
        assertEquals(Set.of(VM, STORAGE), router.allHandledTypes());
    }

    @Test
    void rejectsDuplicateTypes() {
        DiscoveryAdapter a1 = new StubDiscoveryAdapter(Set.of(VM), List.of());
        DiscoveryAdapter a2 = new StubDiscoveryAdapter(Set.of(VM), List.of());
        assertThrows(IllegalArgumentException.class,
            () -> new DefaultDiscoveryAdapterRouter(List.of(a1, a2)));
    }

    @Test
    void skipsUnhandledTypes() {
        DiscoveryAdapter adapter = new StubDiscoveryAdapter(Set.of(VM), List.of());
        var router = new DefaultDiscoveryAdapterRouter(List.of(adapter));
        var results = router.discover(Set.of(STORAGE), "tenant-1");
        assertTrue(results.isEmpty());
    }

    private record StubDiscoveryAdapter(Set<NodeType> handledTypes,
                                         List<DiscoveredNode> nodes) implements DiscoveryAdapter {
        @Override
        public List<DiscoveredNode> discover(Set<NodeType> types, String tenancyId) {
            return nodes;
        }
    }
}
