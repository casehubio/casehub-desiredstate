package io.casehub.desiredstate.api;

import java.time.Instant;

public record RollbackInitiatedData(
    String tenancyId,
    long fromVersion,
    long toVersion,
    String initiatedBy,
    String rollbackId,
    Instant timestamp
) {}
