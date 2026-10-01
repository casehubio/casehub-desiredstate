package io.casehub.desiredstate.api;

public sealed interface PlanApprovalDecision {
    record AutoApprove() implements PlanApprovalDecision {}
    record RequireApproval(String reason) implements PlanApprovalDecision {}
}
