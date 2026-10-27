package io.casehub.desiredstate.bridge;

import java.util.Map;

public record BridgeReadRequest(
    String typeId,
    String resourceId,
    String tenancyId,
    Map<String, Object> previousState,
    Map<String, Object> desiredSpec
) {
    public BridgeReadRequest(String typeId, String resourceId, String tenancyId) {
        this(typeId, resourceId, tenancyId, Map.of(), Map.of());
    }
}
