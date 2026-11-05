package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.eidos.org.api.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrgDriftPolicyTest {

    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final OrgDriftPolicy policy = new OrgDriftPolicy(instanceStore);
    private final OrgGoalCompilerTest.TestGraphFactory factory = new OrgGoalCompilerTest.TestGraphFactory();

    @Test void nonOrgNodePassesThrough() {
        var node = new DesiredNode(NodeId.of("x"), new StubNodeSpec(), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new DriftContext("t1", graph, new ActualState(Map.of()));

        var result = policy.evaluate(NodeId.of("x"), NodeStatus.DRIFTED, node, ctx);

        assertThat(result).isInstanceOf(DriftDecision.Reconcile.class);
    }

    @Test void memberDepartureTriggersReconcile() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1")
            .members(List.of(
                new Membership("a1", "worker", null),
                new Membership("a2", "worker", null)))
            .build();
        var instance = new GroupInstance("team-1", "team-1", "t1",
            GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
            null, null, null,
            List.of(new ActualMembership("a1", Instant.now())),
            List.of(), List.of(), Instant.now(), Instant.now(), 1);
        instanceStore.save(instance);

        var node = new DesiredNode(NodeId.of("team-1"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new DriftContext("t1", graph,
            new ActualState(Map.of(NodeId.of("team-1"), NodeStatus.DRIFTED)));

        var result = policy.evaluate(NodeId.of("team-1"), NodeStatus.DRIFTED, node, ctx);

        assertThat(result).isInstanceOf(DriftDecision.Reconcile.class);
    }

    @Test void autonomousTopologyChangeExempted() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-2").name("Team 2").tenancyId("t1")
            .topology("swarm").selfDetermination(0.8).build();
        var instance = new GroupInstance("team-2", "team-2", "t1",
            GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
            "mesh", null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 2);
        instanceStore.save(instance);

        var node = new DesiredNode(NodeId.of("team-2"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new DriftContext("t1", graph,
            new ActualState(Map.of(NodeId.of("team-2"), NodeStatus.DRIFTED)));

        var result = policy.evaluate(NodeId.of("team-2"), NodeStatus.DRIFTED, node, ctx);

        assertThat(result).isInstanceOf(DriftDecision.Exempt.class);
    }

    @Test void topologyChangeWithLowAutonomyReconciles() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-3").name("Team 3").tenancyId("t1")
            .topology("swarm").selfDetermination(0.2).build();
        var instance = new GroupInstance("team-3", "team-3", "t1",
            GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
            "mesh", null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 3);
        instanceStore.save(instance);

        var node = new DesiredNode(NodeId.of("team-3"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new DriftContext("t1", graph,
            new ActualState(Map.of(NodeId.of("team-3"), NodeStatus.DRIFTED)));

        var result = policy.evaluate(NodeId.of("team-3"), NodeStatus.DRIFTED, node, ctx);

        assertThat(result).isInstanceOf(DriftDecision.Reconcile.class);
    }

    @Test void absentInstanceReconciles() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-4").name("Team 4").tenancyId("t1").build();
        var node = new DesiredNode(NodeId.of("team-4"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new DriftContext("t1", graph,
            new ActualState(Map.of(NodeId.of("team-4"), NodeStatus.DRIFTED)));

        var result = policy.evaluate(NodeId.of("team-4"), NodeStatus.DRIFTED, node, ctx);

        assertThat(result).isInstanceOf(DriftDecision.Reconcile.class);
    }

    @Test void matchingTopologyReconciles() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-5").name("Team 5").tenancyId("t1")
            .topology("swarm").selfDetermination(0.9).build();
        var instance = new GroupInstance("team-5", "team-5", "t1",
            GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
            "swarm", null, null, List.of(), List.of(), List.of(),
            Instant.now(), Instant.now(), 0);
        instanceStore.save(instance);

        var node = new DesiredNode(NodeId.of("team-5"), new OrgUnitNodeSpec(unit), HumanGating.NONE);
        var graph = factory.of(List.of(node), List.of());
        var ctx = new DriftContext("t1", graph,
            new ActualState(Map.of(NodeId.of("team-5"), NodeStatus.DRIFTED)));

        var result = policy.evaluate(NodeId.of("team-5"), NodeStatus.DRIFTED, node, ctx);

        assertThat(result).isInstanceOf(DriftDecision.Reconcile.class);
    }

    record StubNodeSpec() implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("stub"); }
    }
}
