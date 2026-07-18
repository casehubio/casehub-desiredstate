package io.casehub.desiredstate.work;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.work.api.WorkItemCreateRequest;
import io.casehub.work.api.WorkItemRef;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.api.spi.WorkItemCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class WorkItemPendingApprovalHandlerTest {

    private static final NodeId NODE_1 = NodeId.of("node-1");
    private static final NodeType TYPE = NodeType.of("test");
    private static final NodeSpec SPEC = new StubSpec();
    private static final String TENANCY = "tenant-1";
    private static final String PLAN_REF = "plan-abc-123";

    private InMemoryWorkItemCreator creator;
    private WorkItemPendingApprovalHandler handler;

    @BeforeEach
    void setUp() {
        creator = new InMemoryWorkItemCreator();
        handler = new WorkItemPendingApprovalHandler(creator);
    }

    @Test
    void check_noWorkItem_returnsNone() {
        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    @Test
    void check_activeWorkItem_returnsPending() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Pending.class);
        assertThat(((ApprovalCheckResult.Pending) result).planReference()).isEqualTo(PLAN_REF);
    }

    @Test
    void check_completedWorkItem_returnsApprovedAndDoesNotObsolete() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatusAndAssignee(callerRef, WorkItemStatus.COMPLETED, "alice");

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Approved.class);
        var approved = (ApprovalCheckResult.Approved) result;
        assertThat(approved.approval().planReference()).isEqualTo(PLAN_REF);
        assertThat(approved.approval().approvedBy()).isEqualTo("alice");
        assertThat(approved.approval().approvedAt()).isNotNull();

        // Verify NOT obsoleted — second check should still return Approved
        var secondCheck = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(secondCheck).isInstanceOf(ApprovalCheckResult.Approved.class);
    }

    @Test
    void check_completedWorkItem_nullAssignee_usesSystemFallback() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatus(callerRef, WorkItemStatus.COMPLETED);

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Approved.class);
        assertThat(((ApprovalCheckResult.Approved) result).approval().approvedBy()).isEqualTo("system");
    }

    @Test
    void check_rejectedWorkItem_returnsRejectedWithOutcome() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatusAndOutcome(callerRef, WorkItemStatus.REJECTED, "policy-violation");

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.Rejected.class);
        var rejected = (ApprovalCheckResult.Rejected) result;
        assertThat(rejected.planReference()).isEqualTo(PLAN_REF);
        assertThat(rejected.reason()).contains("rejected");
        assertThat(rejected.reason()).contains("policy-violation");
    }

    @Test
    void check_obsoleteWorkItem_returnsNone() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatus(callerRef, WorkItemStatus.OBSOLETE);

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    @Test
    void recordPending_createsWorkItemWithCorrectCallerRefAndPayload() {
        var outcome = handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);

        assertThat(outcome).isInstanceOf(StepOutcome.Skipped.class);
        assertThat(((StepOutcome.Skipped) outcome).reason()).contains(PLAN_REF);

        assertThat(creator.created).hasSize(1);
        var request = creator.created.get(0);
        assertThat(request.callerRef).isEqualTo(
                "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION");
        assertThat(request.payload).isEqualTo(PLAN_REF);
        assertThat(request.tenancyId).isEqualTo(TENANCY);
        assertThat(request.title).isEqualTo("Approve provision: node-1");
        assertThat(request.types).containsExactly("desiredstate-approval");
        assertThat(request.permittedOutcomes).hasSize(2);
    }

    @Test
    void acknowledgeRejection_obsoletesCallerRef() {
        handler.recordPending(node(), StepAction.PROVISION, TENANCY, PLAN_REF);
        String callerRef = "desiredstate-approval:" + TENANCY + ":" + NODE_1.value() + ":PROVISION";
        creator.setStatus(callerRef, WorkItemStatus.REJECTED);

        handler.acknowledgeRejection(node(), StepAction.PROVISION, TENANCY);

        var result = handler.check(node(), StepAction.PROVISION, TENANCY);
        assertThat(result).isInstanceOf(ApprovalCheckResult.None.class);
    }

    // --- helpers ---

    private DesiredNode node() {
        return new DesiredNode(NODE_1, TYPE, SPEC, HumanGating.NONE);
    }

    private record StubSpec() implements NodeSpec {}

    // --- in-memory WorkItemCreator for testing ---

    static class InMemoryWorkItemCreator implements WorkItemCreator {
        final         List<WorkItemCreateRequest>            created     = new ArrayList<>();
        private final ConcurrentHashMap<String, WorkItemRef> byCallerRef = new ConcurrentHashMap<>();

        @Override
        public WorkItemRef create(WorkItemCreateRequest request) {
            created.add(request);
            var ref = new WorkItemRef(UUID.randomUUID(), WorkItemStatus.PENDING,
                                      request.callerRef, null, null, request.candidateGroups,
                                      null, request.tenancyId, request.payload, null, null);
            byCallerRef.put(request.callerRef, ref);
            return ref;
        }

        @Override
        public Optional<WorkItemRef> findByCallerRef(String callerRef) {
            return Optional.ofNullable(byCallerRef.get(callerRef));
        }

        @Override
        public Optional<WorkItemRef> findActiveByCallerRef(String callerRef) {
            return findByCallerRef(callerRef).filter(r -> r.status().isActive());
        }

        @Override
        public void obsoleteByCallerRef(String callerRef) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                                                            new WorkItemRef(ref.id(), WorkItemStatus.OBSOLETE, ref.callerRef(),
                                                                            ref.assigneeId(), ref.resolution(), ref.candidateGroups(),
                                                                            ref.outcome(), ref.tenancyId(), ref.payload(),
                                                                            ref.payloadTypeName(), ref.resolutionTypeName()));
        }

        void setStatus(String callerRef, WorkItemStatus status) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                                                            new WorkItemRef(ref.id(), status, ref.callerRef(),
                                                                            ref.assigneeId(), ref.resolution(), ref.candidateGroups(),
                                                                            ref.outcome(), ref.tenancyId(), ref.payload(),
                                                                            ref.payloadTypeName(), ref.resolutionTypeName()));
        }

        void setStatusAndAssignee(String callerRef, WorkItemStatus status, String assigneeId) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                                                            new WorkItemRef(ref.id(), status, ref.callerRef(),
                                                                            assigneeId, ref.resolution(), ref.candidateGroups(),
                                                                            ref.outcome(), ref.tenancyId(), ref.payload(),
                                                                            ref.payloadTypeName(), ref.resolutionTypeName()));
        }

        void setStatusAndOutcome(String callerRef, WorkItemStatus status, String outcome) {
            byCallerRef.computeIfPresent(callerRef, (k, ref) ->
                                                            new WorkItemRef(ref.id(), status, ref.callerRef(),
                                                                            ref.assigneeId(), ref.resolution(), ref.candidateGroups(),
                                                                            outcome, ref.tenancyId(), ref.payload(),
                                                                            ref.payloadTypeName(), ref.resolutionTypeName()));
        }
    }
}
