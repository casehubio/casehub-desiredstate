package io.casehub.desiredstate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypesTest {
    record TestSpec(String name) implements NodeSpec {}

    // Minimal test stub for DesiredStateGraph
    private static final DesiredStateGraph EMPTY_GRAPH = new DesiredStateGraph() {
        @Override public Map<NodeId, DesiredNode> nodes() { return Map.of(); }
        @Override public Set<Dependency> dependencies() { return Set.of(); }
        @Override public Set<NodeId> dependenciesOf(NodeId node) { return Set.of(); }
        @Override public Set<NodeId> dependentsOf(NodeId node) { return Set.of(); }
        @Override public Set<NodeId> roots() { return Set.of(); }
        @Override public Set<NodeId> leaves() { return Set.of(); }
        @Override public int version() { return 0; }
        @Override public boolean isEmpty() { return true; }
        @Override public DesiredStateGraph withNode(DesiredNode node) { return this; }
        @Override public DesiredStateGraph withoutNode(NodeId id) { return this; }
        @Override public DesiredStateGraph withDependency(Dependency dep) { return this; }
        @Override public DesiredStateGraph withoutDependency(Dependency dep) { return this; }
        @Override public DesiredStateGraph withMutation(GraphMutation mutation) { return this; }
        @Override public DesiredStateGraph overlay(DesiredStateGraph other) { return this; }
        @Override public DesiredStateGraph connect(DesiredStateGraph other) { return this; }
    };

    @Test void graphMutation_sealedExhaustive() {
        var node = new DesiredNode(new NodeId("a"), new NodeType("t"), new TestSpec("x"), HumanGating.NONE);
        GraphMutation mutation = new GraphMutation.AddNode(node);
        String result = switch (mutation) {
            case GraphMutation.AddNode m -> "add:" + m.node().id().value();
            case GraphMutation.RemoveNode m -> "remove:" + m.id().value();
            case GraphMutation.UpdateNode m -> "update:" + m.id().value();
            case GraphMutation.AddDependency m -> "addDep:" + m.dependency().from().value();
            case GraphMutation.RemoveDependency m -> "rmDep:" + m.dependency().from().value();
        };
        assertThat(result).isEqualTo("add:a");
    }

    @Test void provisionResult_sealed() {
        ProvisionResult success = new ProvisionResult.Success();
        ProvisionResult failed = new ProvisionResult.Failed("timeout");
        assertThat(success).isInstanceOf(ProvisionResult.Success.class);
        assertThat(((ProvisionResult.Failed) failed).reason()).isEqualTo("timeout");
    }

    @Test void provisionResult_pendingApproval() {
        var nodeId = new NodeId("db-prod");
        ProvisionResult pa = new ProvisionResult.PendingApproval(nodeId, "plan-ref-123");
        assertThat(pa).isInstanceOf(ProvisionResult.PendingApproval.class);
        assertThat(((ProvisionResult.PendingApproval) pa).nodeId()).isEqualTo(nodeId);
        assertThat(((ProvisionResult.PendingApproval) pa).planReference()).isEqualTo("plan-ref-123");
    }

    @Test void deprovisionResult_sealed() {
        DeprovisionResult failed = new DeprovisionResult.Failed("locked");
        assertThat(((DeprovisionResult.Failed) failed).reason()).isEqualTo("locked");
    }

    @Test void deprovisionResult_pendingApproval() {
        var nodeId = new NodeId("db-prod");
        DeprovisionResult pa = new DeprovisionResult.PendingApproval(nodeId, "destroy-plan-456");
        assertThat(pa).isInstanceOf(DeprovisionResult.PendingApproval.class);
        assertThat(((DeprovisionResult.PendingApproval) pa).nodeId()).isEqualTo(nodeId);
        assertThat(((DeprovisionResult.PendingApproval) pa).planReference()).isEqualTo("destroy-plan-456");
    }

    @Test void stepOutcome_sealed() {
        StepOutcome outcome = new StepOutcome.Failed("boom");
        String result = switch (outcome) {
            case StepOutcome.Succeeded s -> "ok";
            case StepOutcome.Failed f -> "fail:" + f.reason();
            case StepOutcome.Skipped s -> "skip:" + s.reason();
            case StepOutcome.Rejected r -> "reject:" + r.reason();
        };
        assertThat(result).isEqualTo("fail:boom");
    }

    @Test void stepOutcome_rejected() {
        StepOutcome outcome = new StepOutcome.Rejected("human said no");
        assertThat(outcome).isInstanceOf(StepOutcome.Rejected.class);
        assertThat(((StepOutcome.Rejected) outcome).reason()).isEqualTo("human said no");
    }

    @Test void transitionResult_outcomes() {
        var id = new NodeId("a");
        var result = new TransitionResult(Map.of(id, new StepOutcome.Succeeded()));
        assertThat(result.outcomes()).containsKey(id);
    }

    @Test void actualState_statuses() {
        var id = new NodeId("lib");
        var state = new ActualState(Map.of(id, NodeStatus.PRESENT));
        assertThat(state.statuses().get(id)).isEqualTo(NodeStatus.PRESENT);
    }

    @Test void faultEvent_fields() {
        var event = new FaultEvent(new NodeId("lib"), FaultType.NODE_DESTROYED, "hero raid");
        assertThat(event.type()).isEqualTo(FaultType.NODE_DESTROYED);
    }

    @Test void stateEvent_fields() {
        var event = new StateEvent(new NodeId("lib"), NodeStatus.ABSENT, "destroyed");
        assertThat(event.newStatus()).isEqualTo(NodeStatus.ABSENT);
    }

    @Test void reconciliationResult_fields() {
        var result = new ReconciliationResult(
            Set.of(new NodeId("a")), Set.of(new NodeId("b")), Set.of(), List.of());
        assertThat(result.resolved()).hasSize(1);
        assertThat(result.drifted()).hasSize(1);
    }

    @Test void planApproval_fields() {
        var approval = new PlanApproval("plan-42", "jane", Instant.parse("2026-06-28T14:30:00Z"));
        assertThat(approval.planReference()).isEqualTo("plan-42");
        assertThat(approval.approvedBy()).isEqualTo("jane");
        assertThat(approval.approvedAt()).isEqualTo(Instant.parse("2026-06-28T14:30:00Z"));
    }

    @Test void planApproval_rejectsNulls() {
        assertThatThrownBy(() -> new PlanApproval(null, "jane", Instant.now()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanApproval("plan", null, Instant.now()))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PlanApproval("plan", "jane", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void approvalCheckResult_sealedExhaustive() {
        ApprovalCheckResult result = new ApprovalCheckResult.None();
        String out = switch (result) {
            case ApprovalCheckResult.None n -> "none";
            case ApprovalCheckResult.Pending p -> "pending:" + p.planReference();
            case ApprovalCheckResult.Approved a -> "approved:" + a.approval().approvedBy();
            case ApprovalCheckResult.Rejected r -> "rejected:" + r.reason();
        };
        assertThat(out).isEqualTo("none");
    }

    @Test void provisionContext_withApproval() {
        var ctx = new ProvisionContext("t1", EMPTY_GRAPH);
        assertThat(ctx.hasApproval()).isFalse();
        assertThat(ctx.approval()).isNull();

        var approval = new PlanApproval("plan-1", "jane", Instant.now());
        var enriched = ctx.withApproval(approval);
        assertThat(enriched.hasApproval()).isTrue();
        assertThat(enriched.approval()).isEqualTo(approval);
        assertThat(enriched.tenancyId()).isEqualTo("t1");
        assertThat(enriched.graph()).isSameAs(EMPTY_GRAPH);
    }

    @Test void deprovisionContext_withApproval() {
        var ctx = new DeprovisionContext("t1", EMPTY_GRAPH);
        assertThat(ctx.hasApproval()).isFalse();

        var approval = new PlanApproval("plan-1", "jane", Instant.now());
        var enriched = ctx.withApproval(approval);
        assertThat(enriched.hasApproval()).isTrue();
        assertThat(enriched.approval()).isEqualTo(approval);
    }
}
