package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.PlanApprovalDecision;
import io.casehub.desiredstate.api.PlanApprovalPolicy;
import io.casehub.desiredstate.api.TransitionPlan;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpPlanApprovalPolicy implements PlanApprovalPolicy {
    @Override
    public PlanApprovalDecision evaluate(TransitionPlan plan, String tenancyId) {
        return new PlanApprovalDecision.AutoApprove();
    }
}
