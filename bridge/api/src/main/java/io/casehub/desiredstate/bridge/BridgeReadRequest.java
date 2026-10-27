package io.casehub.desiredstate.bridge;

public record BridgeReadRequest(String typeId, String resourceId, String tenancyId) {}
