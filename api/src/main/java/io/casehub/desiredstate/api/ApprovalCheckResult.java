package io.casehub.desiredstate.api;

public sealed interface ApprovalCheckResult {
    record None() implements ApprovalCheckResult {}
    record Pending(String planReference) implements ApprovalCheckResult {}
    record Approved(PlanApproval approval) implements ApprovalCheckResult {}
    record Rejected(String planReference, String reason) implements ApprovalCheckResult {}
}
