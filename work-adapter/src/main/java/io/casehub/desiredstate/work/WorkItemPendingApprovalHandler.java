package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.PendingApprovalHandler;
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.work.api.Outcome;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.spi.WorkItemCreator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class WorkItemPendingApprovalHandler implements PendingApprovalHandler {

    private final WorkItemCreator workItemCreator;

    @Inject
    public WorkItemPendingApprovalHandler(WorkItemCreator workItemCreator) {
        this.workItemCreator = workItemCreator;
    }

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        String callerRef = callerRef(node.id(), action, tenancyId);
        return workItemCreator.findByCallerRef(callerRef)
                .map(ref -> mapStatus(ref, node.id()))
                .orElse(new ApprovalCheckResult.None());
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        String callerRef = callerRef(node.id(), action, tenancyId);
        workItemCreator.obsoleteByCallerRef(callerRef);
        workItemCreator.create(WorkItemCreateRequest.builder()
                .title("Approve " + action.name().toLowerCase() + ": " + node.id().value())
                .callerRef(callerRef)
                .payload(planReference)
                .tenancyId(tenancyId)
                .types(List.of("desiredstate-approval"))
                .permittedOutcomes(List.of(
                        new Outcome("approve", "Approve", null),
                        new Outcome("reject", "Reject", null)))
                .build());
        return new StepOutcome.Skipped("pending approval: " + planReference);
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
        workItemCreator.obsoleteByCallerRef(callerRef(node.id(), action, tenancyId));
    }

    private ApprovalCheckResult mapStatus(WorkItemRef ref, NodeId nodeId) {
        return switch (ref.status()) {
            case COMPLETED -> new ApprovalCheckResult.Approved(new PlanApproval(
                    ref.payload() != null ? ref.payload() : "",
                    ref.assigneeId() != null ? ref.assigneeId() : "system",
                    Instant.now()));
            case REJECTED, CANCELLED, EXPIRED, FAULTED, ESCALATED -> {
                String reason = "approval " + ref.status().name().toLowerCase()
                        + " for node " + nodeId.value();
                if (ref.outcome() != null && !ref.outcome().isBlank()) {
                    reason += ": " + ref.outcome();
                }
                yield new ApprovalCheckResult.Rejected(
                        ref.payload() != null ? ref.payload() : "", reason);
            }
            case OBSOLETE -> new ApprovalCheckResult.None();
            default -> new ApprovalCheckResult.Pending(
                    ref.payload() != null ? ref.payload() : "");
        };
    }

    private String callerRef(NodeId nodeId, StepAction action, String tenancyId) {
        return "desiredstate-approval:" + tenancyId + ":" + nodeId.value()
                + ":" + action.name();
    }
}
