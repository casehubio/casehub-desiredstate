package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.DiscoveryAdapter;
import io.casehub.desiredstate.api.InMemoryReconciliationStateStore;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryOrchestratorTest {

    private static final NodeType VM = NodeType.of("cloud:vm");
    private static final NodeType STORAGE = NodeType.of("cloud:storage");

    @Test
    void discoversResourcesAndBuildsGraph() {
        DiscoveryAdapter adapter = new StubDiscoveryAdapter(
            Set.of(VM),
            List.of(
                new DiscoveredNode("i-1", VM, Map.of("status", "running"),
                    Map.of("name", "vm-1", "instance_type", "t3.medium")),
                new DiscoveredNode("i-2", VM, Map.of("status", "running"),
                    Map.of("name", "vm-2", "instance_type", "t3.large"))
            )
        );
        var router = new DefaultDiscoveryAdapterRouter(List.of(adapter));
        var stateStore = new InMemoryReconciliationStateStore();
        var graphFactory = new DefaultDesiredStateGraphFactory();

        var orchestrator = new DiscoveryOrchestrator(router, graphFactory, stateStore);
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(VM), "tenant-1", "staging", "infra"));

        assertEquals(2, result.resources().size());
        assertEquals(2, result.graph().nodes().size());
        assertTrue(result.graph().dependencies().isEmpty());
    }

    @Test
    void discoversAllTypesWhenEmptyTypesRequested() {
        DiscoveryAdapter vmAdapter = new StubDiscoveryAdapter(Set.of(VM),
            List.of(new DiscoveredNode("i-1", VM, Map.of(), Map.of("name", "vm-1"))));
        DiscoveryAdapter storageAdapter = new StubDiscoveryAdapter(Set.of(STORAGE),
            List.of(new DiscoveredNode("s-1", STORAGE, Map.of(), Map.of("name", "bucket-1"))));
        var router = new DefaultDiscoveryAdapterRouter(List.of(vmAdapter, storageAdapter));
        var stateStore = new InMemoryReconciliationStateStore();
        var graphFactory = new DefaultDesiredStateGraphFactory();

        var orchestrator = new DiscoveryOrchestrator(router, graphFactory, stateStore);
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(), "tenant-1", "staging", "infra"));

        assertEquals(2, result.resources().size());
    }

    @Test
    void adoptBaselineStoresGraphInReconciliationStateStore() {
        DiscoveryAdapter adapter = new StubDiscoveryAdapter(Set.of(VM),
            List.of(new DiscoveredNode("i-1", VM, Map.of(), Map.of("name", "vm-1"))));
        var router = new DefaultDiscoveryAdapterRouter(List.of(adapter));
        var stateStore = new InMemoryReconciliationStateStore();
        var graphFactory = new DefaultDesiredStateGraphFactory();

        var orchestrator = new DiscoveryOrchestrator(router, graphFactory, stateStore);
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(VM), "tenant-1", "staging", "infra"));

        assertTrue(stateStore.load("tenant-1").isEmpty());
        orchestrator.adoptBaseline("tenant-1", result.graph());
        assertTrue(stateStore.load("tenant-1").isPresent());
        assertEquals(1, stateStore.load("tenant-1").get().nodes().size());
    }

    @Test
    void sanitizesResourceIdsForNodeIds() {
        DiscoveryAdapter adapter = new StubDiscoveryAdapter(Set.of(VM),
            List.of(new DiscoveredNode("i-ABC_123.test", VM, Map.of(), Map.of("name", "vm-1"))));
        var router = new DefaultDiscoveryAdapterRouter(List.of(adapter));
        var stateStore = new InMemoryReconciliationStateStore();
        var graphFactory = new DefaultDesiredStateGraphFactory();

        var orchestrator = new DiscoveryOrchestrator(router, graphFactory, stateStore);
        var result = orchestrator.discover(
            new DiscoveryRequest(Set.of(VM), "tenant-1", "staging", "infra"));

        var nodeId = result.graph().nodes().keySet().iterator().next();
        assertEquals("i-abc-123-test", nodeId.value());
    }

    private record StubDiscoveryAdapter(Set<NodeType> handledTypes,
                                         List<DiscoveredNode> nodes) implements DiscoveryAdapter {
        @Override
        public List<DiscoveredNode> discover(Set<NodeType> types, String tenancyId) {
            return nodes;
        }
    }
}
