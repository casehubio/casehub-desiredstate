package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ApprovalCheckResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GateDecision;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.PlanApproval;
import io.casehub.desiredstate.api.PlanApprovalDecision;
import io.casehub.desiredstate.api.PlanApprovalHandler;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.TransitionPlan;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlanApprovalGateTest {

    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    record SimpleSpec() implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("simple"); }
    }

    private TransitionPlan makePlan() {
        DesiredStateGraph before = factory.empty();
        DesiredNode node = new DesiredNode(NodeId.of("n1"), new SimpleSpec(), HumanGating.NONE);
        DesiredStateGraph after = factory.of(List.of(node), List.of());
        return new TransitionPlan(
            List.of(), List.of(), List.of(),
            List.of(List.of(new OrderedStep(node, StepAction.PROVISION))),
            before, after);
    }

    @Test
    void checkPending_noPendingPlan_returnsEmpty() {
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.AutoApprove(),
            new StubHandler());
        Optional<GateDecision> result = gate.checkPending("t1", 1);
        assertThat(result).isEmpty();
    }

    @Test
    void evaluateNewPlan_autoApprove_returnsExecute() {
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.AutoApprove(),
            new StubHandler());
        TransitionPlan plan = makePlan();
        GateDecision decision = gate.evaluateNewPlan(plan, "t1");
        assertThat(decision).isInstanceOf(GateDecision.Execute.class);
        assertThat(((GateDecision.Execute) decision).plan()).isSameAs(plan);
    }

    @Test
    void evaluateNewPlan_requireApproval_storesAndReturnsAwaiting() {
        StubHandler handler = new StubHandler();
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.RequireApproval("topology change"),
            handler);
        TransitionPlan plan = makePlan();
        GateDecision decision = gate.evaluateNewPlan(plan, "t1");
        assertThat(decision).isInstanceOf(GateDecision.AwaitingApproval.class);
        assertThat(handler.submitted).isTrue();
    }

    @Test
    void checkPending_versionMatch_approved_returnsExecute() {
        StubHandler handler = new StubHandler();
        handler.checkResult = new ApprovalCheckResult.Approved(
            new PlanApproval("ref1", "admin", Instant.now()));
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.RequireApproval("review"),
            handler);
        TransitionPlan plan = makePlan();
        gate.evaluateNewPlan(plan, "t1");

        Optional<GateDecision> result = gate.checkPending("t1", plan.after().version());
        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(GateDecision.Execute.class);
    }

    @Test
    void checkPending_versionMatch_stillPending_returnsAwaiting() {
        StubHandler handler = new StubHandler();
        handler.checkResult = new ApprovalCheckResult.Pending("ref1");
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.RequireApproval("review"),
            handler);
        TransitionPlan plan = makePlan();
        gate.evaluateNewPlan(plan, "t1");

        Optional<GateDecision> result = gate.checkPending("t1", plan.after().version());
        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(GateDecision.AwaitingApproval.class);
    }

    @Test
    void checkPending_versionMismatch_invalidatesAndReturnsEmpty() {
        StubHandler handler = new StubHandler();
        handler.checkResult = new ApprovalCheckResult.Pending("ref1");
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.RequireApproval("review"),
            handler);
        TransitionPlan plan = makePlan();
        gate.evaluateNewPlan(plan, "t1");

        Optional<GateDecision> result = gate.checkPending("t1", plan.after().version() + 99);
        assertThat(result).isEmpty();
        assertThat(handler.cancelled).isTrue();
    }

    @Test
    void checkPending_versionMatch_rejected_returnsRejected() {
        StubHandler handler = new StubHandler();
        handler.checkResult = new ApprovalCheckResult.Rejected("ref1", "too risky");
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.RequireApproval("review"),
            handler);
        TransitionPlan plan = makePlan();
        gate.evaluateNewPlan(plan, "t1");

        Optional<GateDecision> result = gate.checkPending("t1", plan.after().version());
        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(GateDecision.Rejected.class);
        assertThat(((GateDecision.Rejected) result.get()).reason()).isEqualTo("too risky");
    }

    @Test
    void checkPending_afterApproval_pendingPlanCleared() {
        StubHandler handler = new StubHandler();
        handler.checkResult = new ApprovalCheckResult.Approved(
            new PlanApproval("ref1", "admin", Instant.now()));
        PlanApprovalGate gate = new PlanApprovalGate(
            (plan, tid) -> new PlanApprovalDecision.RequireApproval("review"),
            handler);
        TransitionPlan plan = makePlan();
        gate.evaluateNewPlan(plan, "t1");
        gate.checkPending("t1", plan.after().version());

        Optional<GateDecision> secondCheck = gate.checkPending("t1", plan.after().version());
        assertThat(secondCheck).isEmpty();
    }

    static class StubHandler implements PlanApprovalHandler {
        boolean submitted = false;
        boolean cancelled = false;
        ApprovalCheckResult checkResult = new ApprovalCheckResult.Pending("ref1");

        @Override
        public String submit(TransitionPlan plan, String tenancyId, String reason) {
            submitted = true;
            return "ref1";
        }
        @Override
        public ApprovalCheckResult check(String planReference, String tenancyId) {
            return checkResult;
        }
        @Override
        public void cancel(String planReference, String tenancyId) {
            cancelled = true;
        }
    }
}
