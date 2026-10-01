package io.casehub.desiredstate.api;

public sealed interface GateDecision {
    record Execute(TransitionPlan plan) implements GateDecision {}
    record AwaitingApproval(String planReference) implements GateDecision {}
    record Rejected(String planReference, String reason) implements GateDecision {}
}
