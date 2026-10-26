package io.casehub.desiredstate.api;

import java.time.Instant;

public record NodeRollbackInitiatedData(
    String tenancyId,
    String nodeId,
    long fromVersion,
    long toVersion,
    String initiatedBy,
    String rollbackId,
    Instant timestamp
) {}
