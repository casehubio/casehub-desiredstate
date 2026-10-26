package io.casehub.desiredstate.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDesiredStateHistoryStoreTest {

    private InMemoryDesiredStateHistoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDesiredStateHistoryStore();
    }

    @Test
    void historyEntryRecordFields() {
        var entry = new HistoryEntry(1L, java.time.Instant.now(), "updateDesired", "admin");
        assertEquals(1L, entry.version());
        assertEquals("updateDesired", entry.source());
        assertEquals("admin", entry.initiatedBy());
    }

    @Test
    void recordAndLoadVersion() {
        DesiredStateGraph graph = stubGraph();
        long version = store.record("tenant-1", graph, "updateDesired", null);
        assertEquals(1L, version);

        var loaded = store.load("tenant-1", version);
        assertTrue(loaded.isPresent());
        assertSame(graph, loaded.get());
    }

    @Test
    void autoIncrementVersionPerTenant() {
        assertEquals(1L, store.record("t1", stubGraph(), "updateDesired", null));
        assertEquals(2L, store.record("t1", stubGraph(), "updateDesired", null));
        assertEquals(1L, store.record("t2", stubGraph(), "updateDesired", null));
    }

    @Test
    void listVersionsDescending() {
        store.record("t1", stubGraph(), "updateDesired", null);
        store.record("t1", stubGraph(), "rollback", "admin");

        List<HistoryEntry> versions = store.listVersions("t1");
        assertEquals(2, versions.size());
        assertEquals(2L, versions.get(0).version());
        assertEquals("rollback", versions.get(0).source());
        assertEquals("admin", versions.get(0).initiatedBy());
        assertEquals(1L, versions.get(1).version());
        assertEquals("updateDesired", versions.get(1).source());
        assertNull(versions.get(1).initiatedBy());
    }

    @Test
    void pruneKeepsNewest() {
        for (int i = 0; i < 5; i++) {
            store.record("t1", stubGraph(), "updateDesired", null);
        }

        store.prune("t1", 3);
        List<HistoryEntry> versions = store.listVersions("t1");
        assertEquals(3, versions.size());
        assertEquals(5L, versions.get(0).version());
        assertEquals(4L, versions.get(1).version());
        assertEquals(3L, versions.get(2).version());
    }

    @Test
    void pruneNoOpWhenUnderLimit() {
        store.record("t1", stubGraph(), "updateDesired", null);
        store.record("t1", stubGraph(), "updateDesired", null);

        store.prune("t1", 5);
        assertEquals(2, store.listVersions("t1").size());
    }

    @Test
    void removeDeletesAllHistory() {
        store.record("t1", stubGraph(), "updateDesired", null);
        store.record("t1", stubGraph(), "updateDesired", null);
        store.remove("t1");

        assertTrue(store.listVersions("t1").isEmpty());
        assertTrue(store.load("t1", 1L).isEmpty());
    }

    @Test
    void loadUnknownVersionReturnsEmpty() {
        assertTrue(store.load("t1", 99L).isEmpty());
    }

    @Test
    void loadUnknownTenantReturnsEmpty() {
        assertTrue(store.load("unknown", 1L).isEmpty());
    }

    @Test
    void listVersionsForUnknownTenantReturnsEmpty() {
        assertTrue(store.listVersions("unknown").isEmpty());
    }

    @Test
    void tenantIsolation() {
        DesiredStateGraph g1 = stubGraph();
        DesiredStateGraph g2 = stubGraph();

        store.record("t1", g1, "updateDesired", null);
        store.record("t2", g2, "updateDesired", null);

        assertSame(g1, store.load("t1", 1L).orElseThrow());
        assertSame(g2, store.load("t2", 1L).orElseThrow());

        store.remove("t1");
        assertTrue(store.load("t1", 1L).isEmpty());
        assertTrue(store.load("t2", 1L).isPresent());
    }

    @Test
    void allTenancyIds() {
        store.record("t1", stubGraph(), "updateDesired", null);
        store.record("t2", stubGraph(), "updateDesired", null);

        var ids = store.allTenancyIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("t1"));
        assertTrue(ids.contains("t2"));
    }

    private static DesiredStateGraph stubGraph() {
        return new StubGraph();
    }

    private static class StubGraph implements DesiredStateGraph {
        @Override public java.util.Map<NodeId, DesiredNode> nodes()          { return java.util.Map.of(); }
        @Override public java.util.Set<Dependency> dependencies()            { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> dependenciesOf(NodeId n)      { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> dependentsOf(NodeId n)        { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> roots()                       { return java.util.Set.of(); }
        @Override public java.util.Set<NodeId> leaves()                      { return java.util.Set.of(); }
        @Override public int version()                                       { return 0; }
        @Override public boolean isEmpty()                                   { return true; }
        @Override public DesiredStateGraph withNode(DesiredNode node)         { return this; }
        @Override public DesiredStateGraph withoutNode(NodeId id)             { return this; }
        @Override public DesiredStateGraph withDependency(Dependency dep)     { return this; }
        @Override public DesiredStateGraph withoutDependency(Dependency dep)  { return this; }
        @Override public DesiredStateGraph withMutation(GraphMutation m)      { return this; }
        @Override public DesiredStateGraph overlay(DesiredStateGraph o)       { return this; }
        @Override public DesiredStateGraph connect(DesiredStateGraph o)       { return this; }
    }
}
