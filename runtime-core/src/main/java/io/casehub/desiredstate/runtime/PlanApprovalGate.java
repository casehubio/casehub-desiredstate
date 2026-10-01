package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.GateDecision;
import io.casehub.desiredstate.api.PendingPlan;
import io.casehub.desiredstate.api.PlanApprovalDecision;
import io.casehub.desiredstate.api.PlanApprovalHandler;
import io.casehub.desiredstate.api.PlanApprovalPolicy;
import io.casehub.desiredstate.api.TransitionPlan;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PlanApprovalGate {

    private final PlanApprovalPolicy policy;
    private final PlanApprovalHandler handler;
    private final ConcurrentHashMap<String, PendingPlan> pendingPlans = new ConcurrentHashMap<>();

    public PlanApprovalGate(PlanApprovalPolicy policy, PlanApprovalHandler handler) {
        this.policy = policy;
        this.handler = handler;
    }

    public Optional<GateDecision> checkPending(String tenancyId, int currentDesiredVersion) {
        PendingPlan pending = pendingPlans.get(tenancyId);
        if (pending == null) {
            return Optional.empty();
        }

        if (pending.desiredVersion() != currentDesiredVersion) {
            pendingPlans.remove(tenancyId);
            handler.cancel(pending.planReference(), tenancyId);
            return Optional.empty();
        }

        ApprovalCheckResult result = handler.check(pending.planReference(), tenancyId);
        return switch (result) {
            case ApprovalCheckResult.Approved approved -> {
                pendingPlans.remove(tenancyId);
                yield Optional.of(new GateDecision.Execute(pending.plan()));
            }
            case ApprovalCheckResult.Pending p ->
                Optional.of(new GateDecision.AwaitingApproval(p.planReference()));
            case ApprovalCheckResult.Rejected rejected -> {
                pendingPlans.remove(tenancyId);
                yield Optional.of(new GateDecision.Rejected(
                    rejected.planReference(), rejected.reason()));
            }
            case ApprovalCheckResult.None none -> {
                pendingPlans.remove(tenancyId);
                yield Optional.empty();
            }
        };
    }

    public GateDecision evaluateNewPlan(TransitionPlan plan, String tenancyId) {
        PlanApprovalDecision decision = policy.evaluate(plan, tenancyId);
        return switch (decision) {
            case PlanApprovalDecision.AutoApprove a ->
                new GateDecision.Execute(plan);
            case PlanApprovalDecision.RequireApproval r -> {
                String reference = handler.submit(plan, tenancyId, r.reason());
                pendingPlans.put(tenancyId,
                    new PendingPlan(plan, reference, plan.after().version()));
                yield new GateDecision.AwaitingApproval(reference);
            }
        };
    }
}
