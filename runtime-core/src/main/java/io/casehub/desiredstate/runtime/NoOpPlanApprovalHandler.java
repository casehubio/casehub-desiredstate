package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.PlanApprovalHandler;
import io.casehub.desiredstate.api.TransitionPlan;

public class NoOpPlanApprovalHandler implements PlanApprovalHandler {
    @Override
    public String submit(TransitionPlan plan, String tenancyId, String reason) {
        return "noop";
    }

    @Override
    public ApprovalCheckResult check(String planReference, String tenancyId) {
        return new ApprovalCheckResult.None();
    }

    @Override
    public void cancel(String planReference, String tenancyId) {}
}
