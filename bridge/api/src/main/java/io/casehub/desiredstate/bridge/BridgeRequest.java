package io.casehub.desiredstate.bridge;

import java.util.Map;

public record BridgeRequest(
    String typeId,
    String resourceId,
    Map<String, Object> spec,
    String tenancyId,
    Map<String, Object> previousState
) {
    public BridgeRequest(String typeId, String resourceId, Map<String, Object> spec, String tenancyId) {
        this(typeId, resourceId, spec, tenancyId, Map.of());
    }
}
