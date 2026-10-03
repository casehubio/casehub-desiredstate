package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;

public class NoOpPendingApprovalHandler implements PendingApprovalHandler {

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        return new ApprovalCheckResult.None();
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        return new StepOutcome.Failed(
            "pending approval: " + planReference + " — no PendingApprovalHandler configured");
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
    }
}
