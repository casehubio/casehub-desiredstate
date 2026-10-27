package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.bridge.BridgeOutputStateStore;

import java.util.Map;
import java.util.Optional;

class NoOpBridgeOutputStateStore implements BridgeOutputStateStore {

    @Override
    public void store(String tenancyId, String nodeId, Map<String, Object> outputState) {}

    @Override
    public Optional<Map<String, Object>> load(String tenancyId, String nodeId) {
        return Optional.empty();
    }

    @Override
    public void remove(String tenancyId, String nodeId) {}
}
