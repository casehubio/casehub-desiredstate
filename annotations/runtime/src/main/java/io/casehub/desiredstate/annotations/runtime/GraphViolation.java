package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.NodeId;

import java.util.List;

public record GraphViolation(
        String invariantName,
        String sourceClassName,
        String message,
        List<NodeId> affectedNodes) {}
