package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.stream.Stream;

@ApplicationScoped
public class OrgPlanApprovalPolicy implements PlanApprovalPolicy {

    private static final double APPROVAL_THRESHOLD = 0.3;

    @Override
    public PlanApprovalDecision evaluate(TransitionPlan plan, String tenancyId, PlanContext context) {
        double minSelfDetermination = allSteps(plan)
            .map(OrderedStep::node)
            .filter(node -> node.spec() instanceof OrgUnitNodeSpec)
            .map(node -> ((OrgUnitNodeSpec) node.spec()).unit())
            .mapToDouble(unit -> unit.selfDetermination())
            .min()
            .orElse(1.0);

        if (minSelfDetermination < APPROVAL_THRESHOLD) {
            return new PlanApprovalDecision.RequireApproval(
                "plan contains org unit with selfDetermination " +
                String.format("%.2f", minSelfDetermination) +
                " (threshold: " + APPROVAL_THRESHOLD + ")");
        }

        return new PlanApprovalDecision.AutoApprove();
    }

    private Stream<OrderedStep> allSteps(TransitionPlan plan) {
        return Stream.of(
            plan.flatRemovals().stream(),
            plan.flatSuspensions().stream(),
            plan.flatResumptions().stream(),
            plan.flatAdditions().stream()
        ).flatMap(s -> s);
    }
}
