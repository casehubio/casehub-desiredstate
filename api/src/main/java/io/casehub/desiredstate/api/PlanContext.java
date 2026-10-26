package io.casehub.desiredstate.api;

public sealed interface PlanContext {
    record Normal() implements PlanContext {}
    record Rollback(long fromVersion, long toVersion, String reason, String rollbackId)
        implements PlanContext {}
}
