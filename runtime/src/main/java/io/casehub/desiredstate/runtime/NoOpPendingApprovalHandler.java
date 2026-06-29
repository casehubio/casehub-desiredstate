package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Default no-op handler for PendingApproval results.
 * Returns {@link ApprovalCheckResult.None} on check (no prior approval state),
 * and {@link StepOutcome.Failed} on recordPending (no handler configured to
 * create a WorkItem or other approval mechanism).
 */
@DefaultBean
@ApplicationScoped
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
        // No-op — nothing to acknowledge when no handler is configured
    }
}
