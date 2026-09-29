package io.casehub.desiredstate.api;

public record LifecycleStateEnteredData(
    String tenancyId,
    String nodeId,
    String nodeType,
    String state,
    String previousState,
    String customEventType
) {}
