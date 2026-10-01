package io.casehub.desiredstate.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryExemptionStoreTest {

    private InMemoryExemptionStore store;
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    @BeforeEach
    void setUp() { store = new InMemoryExemptionStore(); }

    private Exemption exemption(String nodeId) {
        return new Exemption(NodeId.of(nodeId),
            new ExemptionSpec(new RevertCondition.Never(), Map.of()),
            NOW, null);
    }

    @Test
    void grant_and_get() {
        store.grant("t1", NodeId.of("a"), exemption("a"));
        assertTrue(store.get("t1", NodeId.of("a")).isPresent());
    }

    @Test
    void get_absent() {
        assertTrue(store.get("t1", NodeId.of("a")).isEmpty());
    }

    @Test
    void revoke() {
        store.grant("t1", NodeId.of("a"), exemption("a"));
        store.revoke("t1", NodeId.of("a"));
        assertTrue(store.get("t1", NodeId.of("a")).isEmpty());
    }

    @Test
    void grant_overwrites() {
        var e1 = new Exemption(NodeId.of("a"),
            new ExemptionSpec(new RevertCondition.Never(), Map.of()), NOW, null);
        var e2 = new Exemption(NodeId.of("a"),
            new ExemptionSpec(new RevertCondition.Never(), Map.of("k", "v")), NOW, null);
        store.grant("t1", NodeId.of("a"), e1);
        store.grant("t1", NodeId.of("a"), e2);
        assertEquals("v", store.get("t1", NodeId.of("a")).orElseThrow().spec().metadata().get("k"));
    }

    @Test
    void getActive_returns_all() {
        store.grant("t1", NodeId.of("a"), exemption("a"));
        store.grant("t1", NodeId.of("b"), exemption("b"));
        assertEquals(2, store.getActive("t1").size());
    }

    @Test
    void getActive_isolates_tenants() {
        store.grant("t1", NodeId.of("a"), exemption("a"));
        store.grant("t2", NodeId.of("b"), exemption("b"));
        assertEquals(1, store.getActive("t1").size());
    }

    @Test
    void evict_removes_non_retained() {
        store.grant("t1", NodeId.of("a"), exemption("a"));
        store.grant("t1", NodeId.of("b"), exemption("b"));
        store.evict("t1", Set.of(NodeId.of("a")));
        assertTrue(store.get("t1", NodeId.of("a")).isPresent());
        assertTrue(store.get("t1", NodeId.of("b")).isEmpty());
    }
}
