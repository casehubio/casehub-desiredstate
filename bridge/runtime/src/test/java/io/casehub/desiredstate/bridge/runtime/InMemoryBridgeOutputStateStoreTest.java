package io.casehub.desiredstate.bridge.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBridgeOutputStateStoreTest {

    @Test
    void storeAndLoadRoundTrip() {
        var store = new InMemoryBridgeOutputStateStore();
        store.store("tenant-1", "node-1", Map.of("id", "i-abc123", "arn", "arn:aws:..."));
        var loaded = store.load("tenant-1", "node-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get()).containsEntry("id", "i-abc123");
    }

    @Test
    void loadReturnsEmptyForUnknownNode() {
        var store = new InMemoryBridgeOutputStateStore();
        assertThat(store.load("tenant-1", "unknown")).isEmpty();
    }

    @Test
    void removeDeletesState() {
        var store = new InMemoryBridgeOutputStateStore();
        store.store("tenant-1", "node-1", Map.of("id", "i-abc123"));
        store.remove("tenant-1", "node-1");
        assertThat(store.load("tenant-1", "node-1")).isEmpty();
    }

    @Test
    void tenantIsolation() {
        var store = new InMemoryBridgeOutputStateStore();
        store.store("tenant-1", "node-1", Map.of("id", "i-111"));
        store.store("tenant-2", "node-1", Map.of("id", "i-222"));
        assertThat(store.load("tenant-1", "node-1").get()).containsEntry("id", "i-111");
        assertThat(store.load("tenant-2", "node-1").get()).containsEntry("id", "i-222");
    }

    @Test
    void storeOverwritesPreviousValue() {
        var store = new InMemoryBridgeOutputStateStore();
        store.store("tenant-1", "node-1", Map.of("id", "old"));
        store.store("tenant-1", "node-1", Map.of("id", "new"));
        assertThat(store.load("tenant-1", "node-1").get()).containsEntry("id", "new");
    }

    @Test
    void storedStateIsDefensivelyCopied() {
        var store = new InMemoryBridgeOutputStateStore();
        var mutable = new java.util.HashMap<String, Object>();
        mutable.put("id", "original");
        store.store("tenant-1", "node-1", mutable);
        mutable.put("id", "mutated");
        assertThat(store.load("tenant-1", "node-1").get()).containsEntry("id", "original");
    }
}
