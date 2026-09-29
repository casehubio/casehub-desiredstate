package io.casehub.desiredstate.api;

public record LifecycleStateExitedData(
    String tenancyId,
    String nodeId,
    String nodeType,
    String state,
    String nextState,
    String customEventType
) {}
