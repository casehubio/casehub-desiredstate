package io.casehub.desiredstate.api;

public interface PlanApprovalHandler {
    String submit(TransitionPlan plan, String tenancyId, String reason);
    ApprovalCheckResult check(String planReference, String tenancyId);
    void cancel(String planReference, String tenancyId);
}
