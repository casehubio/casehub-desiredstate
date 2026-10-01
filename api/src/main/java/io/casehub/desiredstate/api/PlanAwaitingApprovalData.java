package io.casehub.desiredstate.api;

public record PlanAwaitingApprovalData(
        String tenancyId, String planReference,
        int additionCount, int removalCount, int suspensionCount, int resumptionCount,
        String reason) {}
