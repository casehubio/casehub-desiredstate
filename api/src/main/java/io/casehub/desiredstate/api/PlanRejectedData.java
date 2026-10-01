package io.casehub.desiredstate.api;

public record PlanRejectedData(String tenancyId, String planReference, String reason) {}
