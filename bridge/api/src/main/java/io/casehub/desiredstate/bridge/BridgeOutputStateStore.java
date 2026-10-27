package io.casehub.desiredstate.bridge;

import java.util.Map;
import java.util.Optional;

public interface BridgeOutputStateStore {
    void store(String tenancyId, String nodeId, Map<String, Object> outputState);
    Optional<Map<String, Object>> load(String tenancyId, String nodeId);
    void remove(String tenancyId, String nodeId);
}
