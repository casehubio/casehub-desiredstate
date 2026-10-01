package io.casehub.desiredstate.api;

public interface PlanApprovalPolicy {
    PlanApprovalDecision evaluate(TransitionPlan plan, String tenancyId);
}
