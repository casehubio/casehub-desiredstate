package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.PlanApprovalDecision;
import io.casehub.desiredstate.api.PlanApprovalPolicy;
import io.casehub.desiredstate.api.TransitionPlan;

public class NoOpPlanApprovalPolicy implements PlanApprovalPolicy {
    @Override
    public PlanApprovalDecision evaluate(TransitionPlan plan, String tenancyId) {
        return new PlanApprovalDecision.AutoApprove();
    }
}
