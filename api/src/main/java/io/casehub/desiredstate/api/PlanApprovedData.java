package io.casehub.desiredstate.api;

public record PlanApprovedData(String tenancyId, String planReference, PlanApproval approval) {}
