package io.casehub.desiredstate.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryReconciliationStateStoreTest {

    private InMemoryReconciliationStateStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryReconciliationStateStore();
    }

    @Test
    void load_returnsEmpty_whenNothingStored() {
        Optional<DesiredStateGraph> result = store.load("tenant-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void store_thenLoad_returnsStoredGraph() {
        DesiredStateGraph graph = stubGraph();

        store.store("tenant-1", graph);

        Optional<DesiredStateGraph> result = store.load("tenant-1");
        assertTrue(result.isPresent());
        assertSame(graph, result.get());
    }

    @Test
    void store_overwritesPreviousValue() {
        DesiredStateGraph graph1 = stubGraph();
        DesiredStateGraph graph2 = stubGraph();

        store.store("tenant-1", graph1);
        store.store("tenant-1", graph2);

        Optional<DesiredStateGraph> result = store.load("tenant-1");
        assertTrue(result.isPresent());
        assertSame(graph2, result.get());
    }

    @Test
    void remove_clearsStoredGraph() {
        DesiredStateGraph graph = stubGraph();

        store.store("tenant-1", graph);
        store.remove("tenant-1");

        assertTrue(store.load("tenant-1").isEmpty());
    }

    @Test
    void tenantIsolation_storesAreIndependent() {
        DesiredStateGraph graph1 = stubGraph();
        DesiredStateGraph graph2 = stubGraph();

        store.store("tenant-1", graph1);
        store.store("tenant-2", graph2);

        assertSame(graph1, store.load("tenant-1").orElseThrow());
        assertSame(graph2, store.load("tenant-2").orElseThrow());

        store.remove("tenant-1");
        assertTrue(store.load("tenant-1").isEmpty());
        assertTrue(store.load("tenant-2").isPresent());
    }

    @Test
    void allTenancyIds_emptyWhenNoStores() {
        assertTrue(store.allTenancyIds().isEmpty());
    }

    @Test
    void allTenancyIds_returnsAllStoredTenants() {
        store.store("tenant-1", stubGraph());
        store.store("tenant-2", stubGraph());
        store.store("tenant-3", stubGraph());

        java.util.Set<String> ids = store.allTenancyIds();
        assertTrue(ids.contains("tenant-1"));
        assertTrue(ids.contains("tenant-2"));
        assertTrue(ids.contains("tenant-3"));
        assertTrue(ids.size() == 3);
    }

    @Test
    void allTenancyIds_excludesRemovedTenants() {
        store.store("tenant-1", stubGraph());
        store.store("tenant-2", stubGraph());
        store.remove("tenant-1");

        java.util.Set<String> ids = store.allTenancyIds();
        assertTrue(!ids.contains("tenant-1"));
        assertTrue(ids.contains("tenant-2"));
        assertTrue(ids.size() == 1);
    }


    private static DesiredStateGraph stubGraph() {
        return new StubGraph();
    }

    private static class StubGraph implements DesiredStateGraph {
        @Override
        public java.util.Map<NodeId, DesiredNode> nodes()          {return java.util.Map.of();}

        @Override
        public java.util.Set<Dependency> dependencies()            {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> dependenciesOf(NodeId n)      {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> dependentsOf(NodeId n)        {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> roots()                       {return java.util.Set.of();}

        @Override
        public java.util.Set<NodeId> leaves()                      {return java.util.Set.of();}

        @Override
        public int version()                                       {return 0;}

        @Override
        public boolean isEmpty()                                   {return true;}

        @Override
        public DesiredStateGraph withNode(DesiredNode node)        {return this;}

        @Override
        public DesiredStateGraph withoutNode(NodeId id)            {return this;}

        @Override
        public DesiredStateGraph withDependency(Dependency dep)    {return this;}

        @Override
        public DesiredStateGraph withoutDependency(Dependency dep) {return this;}

        @Override
        public DesiredStateGraph withMutation(GraphMutation m)     {return this;}

        @Override
        public DesiredStateGraph overlay(DesiredStateGraph o)      {return this;}

        @Override
        public DesiredStateGraph connect(DesiredStateGraph o)      {return this;}
    }
}
