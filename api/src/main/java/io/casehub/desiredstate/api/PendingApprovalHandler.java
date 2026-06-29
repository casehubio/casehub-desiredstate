package io.casehub.desiredstate.api;

/**
 * Handles approval lifecycle for nodes whose provisioner returns PendingApproval.
 * Wraps the provisioner — called before (check) and after (recordPending) provisioner.provision().
 *
 * <p>Contrast with {@link HumanNodeHandler} which replaces the provisioner entirely.
 * PendingApprovalHandler is for automated nodes that need human approval before the machine provisions.
 */
public interface PendingApprovalHandler {
    ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId);
    StepOutcome recordPending(DesiredNode node, StepAction action, String tenancyId, String planReference);
    void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId);
}
