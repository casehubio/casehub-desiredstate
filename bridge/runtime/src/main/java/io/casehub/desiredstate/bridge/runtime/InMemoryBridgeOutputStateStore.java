package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.bridge.BridgeOutputStateStore;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryBridgeOutputStateStore implements BridgeOutputStateStore {

    private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    @Override
    public void store(String tenancyId, String nodeId, Map<String, Object> outputState) {
        store.put(key(tenancyId, nodeId), Map.copyOf(outputState));
    }

    @Override
    public Optional<Map<String, Object>> load(String tenancyId, String nodeId) {
        return Optional.ofNullable(store.get(key(tenancyId, nodeId)));
    }

    @Override
    public void remove(String tenancyId, String nodeId) {
        store.remove(key(tenancyId, nodeId));
    }

    private static String key(String tenancyId, String nodeId) {
        return tenancyId + "::" + nodeId;
    }
}
