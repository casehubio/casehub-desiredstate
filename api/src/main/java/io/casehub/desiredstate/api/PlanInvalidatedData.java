package io.casehub.desiredstate.api;

public record PlanInvalidatedData(String tenancyId, String planReference, String reason) {}
