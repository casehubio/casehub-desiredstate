package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.Membership;
import io.casehub.eidos.org.api.OrganizationalUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrgPlanApprovalPolicyTest {

    private final OrgPlanApprovalPolicy policy = new OrgPlanApprovalPolicy();
    private final OrgGoalCompilerTest.TestGraphFactory factory = new OrgGoalCompilerTest.TestGraphFactory();

    @Test void highAutonomyAutoApproves() {
        var plan = planWith(unit("team-a", 0.8));
        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.AutoApprove.class);
    }

    @Test void lowAutonomyRequiresApproval() {
        var plan = planWith(unit("team-b", 0.2));
        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.RequireApproval.class);
    }

    @Test void midRangeAutoApproves() {
        var plan = planWith(unit("team-c", 0.5));
        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.AutoApprove.class);
    }

    @Test void boundaryAtThresholdAutoApproves() {
        var plan = planWith(unit("team-d", 0.3));
        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.AutoApprove.class);
    }

    @Test void justBelowThresholdRequiresApproval() {
        var plan = planWith(unit("team-e", 0.29));
        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.RequireApproval.class);
    }

    @Test void mostRestrictiveGroupGatesPlan() {
        var highUnit = unit("team-high", 0.9);
        var lowUnit = unit("team-low", 0.1);
        var highNode = new DesiredNode(NodeId.of("team-high"), new OrgUnitNodeSpec(highUnit), HumanGating.NONE);
        var lowNode = new DesiredNode(NodeId.of("team-low"), new OrgUnitNodeSpec(lowUnit), HumanGating.ALL);
        var graph = factory.of(List.of(highNode, lowNode), List.of());

        var plan = TransitionPlan.ofFlat(
            List.of(),
            List.of(
                new OrderedStep(highNode, StepAction.PROVISION),
                new OrderedStep(lowNode, StepAction.PROVISION)),
            graph, graph);

        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.RequireApproval.class);
    }

    @Test void planWithNoOrgNodesAutoApproves() {
        var nonOrgNode = new DesiredNode(NodeId.of("infra-1"),
            new StubNodeSpec(), HumanGating.NONE);
        var graph = factory.of(List.of(nonOrgNode), List.of());

        var plan = TransitionPlan.ofFlat(
            List.of(),
            List.of(new OrderedStep(nonOrgNode, StepAction.PROVISION)),
            graph, graph);

        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.AutoApprove.class);
    }

    @Test void emptyPlanAutoApproves() {
        var graph = factory.of(List.of(), List.of());
        var plan = TransitionPlan.ofFlat(List.of(), List.of(), graph, graph);

        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.AutoApprove.class);
    }

    @Test void removalStepsAlsoEvaluated() {
        var lowUnit = unit("team-remove", 0.1);
        var node = new DesiredNode(NodeId.of("team-remove"), new OrgUnitNodeSpec(lowUnit), HumanGating.ALL);
        var graph = factory.of(List.of(node), List.of());

        var plan = TransitionPlan.ofFlat(
            List.of(new OrderedStep(node, StepAction.DEPROVISION)),
            List.of(),
            graph, graph);

        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.RequireApproval.class);
    }

    @Test void requireApprovalIncludesReason() {
        var plan = planWith(unit("team-reason", 0.1));
        var result = policy.evaluate(plan, "t1", new PlanContext.Normal());
        assertThat(result).isInstanceOf(PlanApprovalDecision.RequireApproval.class);
        var reason = ((PlanApprovalDecision.RequireApproval) result).reason();
        assertThat(reason).contains("selfDetermination");
    }

    private TransitionPlan planWith(OrganizationalUnit unit) {
        var node = new DesiredNode(NodeId.of(unit.unitId()), new OrgUnitNodeSpec(unit),
            unit.selfDetermination() < 0.3 ? HumanGating.ALL : HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        return TransitionPlan.ofFlat(
            List.of(),
            List.of(new OrderedStep(node, StepAction.PROVISION)),
            graph, graph);
    }

    private OrganizationalUnit unit(String id, double selfDetermination) {
        return OrganizationalUnit.builder()
            .unitId(id).name(id).tenancyId("t1")
            .selfDetermination(selfDetermination)
            .build();
    }

    record StubNodeSpec() implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("stub"); }
    }
}
