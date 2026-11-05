package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultActualStateAdapterRouter;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.FaultPolicyEngine;
import io.casehub.desiredstate.runtime.LifecycleManager;
import io.casehub.desiredstate.runtime.ReconciliationLoop;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.casehub.eidos.org.api.*;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesiredStateGroupLifecycleServiceTest {

    private final DesiredStateOrgAdapterTest.InMemoryOrgRegistry registry =
        new DesiredStateOrgAdapterTest.InMemoryOrgRegistry();
    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final InMemoryOrgGoalStore goalStore = new InMemoryOrgGoalStore();
    private final OrgGoalCompiler compiler = new OrgGoalCompiler();
    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    private ReconciliationLoop loop;
    private LifecycleManager lifecycleManager;
    private DesiredStateGroupLifecycleService service;

    @BeforeEach
    void setUp() {
        var adapter = new MockActualStateAdapter();
        adapter.setHandledTypes(Set.of(OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP));
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = ReconciliationLoop.builder(
            new TransitionPlanner(), new MockTransitionExecutor(), adapterRouter,
            new FaultPolicyEngine(List.of()), () -> Multi.createFrom().nothing())
            .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofHours(1)).build();
        lifecycleManager = new LifecycleManager(loop);
        service = new DesiredStateGroupLifecycleService(
            registry, instanceStore, goalStore, compiler, graphFactory, loop, lifecycleManager);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    @Test void createGroupAddsUnitToOrgGoalAndStartsLoop() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1")
            .selfDetermination(0.5).build();
        registry.registerUnit(unit);

        var instance = service.createGroup("team-1", "t1", FormationOrigin.TOP_DOWN);

        assertThat(instance).isNotNull();
        assertThat(instance.specId()).isEqualTo("team-1");
        assertThat(instance.state()).isEqualTo(GroupLifecycleState.PROPOSED);
        assertThat(instance.formationOrigin()).isEqualTo(FormationOrigin.TOP_DOWN);
        var goal = goalStore.load("t1");
        assertThat(goal).isPresent();
        assertThat(goal.get().findUnitGoal("team-1")).isPresent();
        assertThat(loop.tenantIds()).contains("t1");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("team-1"));
    }

    @Test void createGroupForMissingSpecThrows() {
        assertThatThrownBy(() -> service.createGroup("missing", "t1", FormationOrigin.TOP_DOWN))
            .isInstanceOf(OrgValidationException.class);
    }

    @Test void proposeGroupSetsProposalOrigin() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-p").name("Team P").tenancyId("t1").build();
        registry.registerUnit(unit);

        var instance = service.proposeGroup("team-p", "t1", "agent-1");

        assertThat(instance.formationOrigin()).isEqualTo(FormationOrigin.PROPOSAL);
    }

    @Test void approveProposalKeepsUnitInGoal() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-a").name("Team A").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-a", "t1", FormationOrigin.PROPOSAL);

        service.approveProposal("team-a", "t1");

        assertThat(goalStore.load("t1").get().findUnitGoal("team-a")).isPresent();
    }

    @Test void rejectProposalRemovesFromOrgGoal() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-r").name("Team R").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-r", "t1", FormationOrigin.PROPOSAL);

        service.rejectProposal("team-r", "t1", "not viable");

        assertThat(goalStore.load("t1").get().findUnitGoal("team-r")).isEmpty();
    }

    @Test void activateGroupKeepsUnitInGoal() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-act").name("Team Act").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-act", "t1", FormationOrigin.TOP_DOWN);

        service.activateGroup("team-act", "t1");

        assertThat(goalStore.load("t1").get().findUnitGoal("team-act")).isPresent();
    }

    @Test void dissolveGroupRemovesUnitFromOrgGoalAndGraph() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-2").name("Team 2").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-2", "t1", FormationOrigin.TOP_DOWN);

        service.dissolveGroup("team-2", "t1", "no longer needed");

        assertThat(goalStore.load("t1").get().findUnitGoal("team-2")).isEmpty();
        assertThat(loop.getDesired("t1").nodes()).doesNotContainKey(NodeId.of("team-2"));
    }

    @Test void secondMutationUsesCasSwap() {
        var unit1 = OrganizationalUnit.builder()
            .unitId("team-1").name("T1").tenancyId("t1").build();
        var unit2 = OrganizationalUnit.builder()
            .unitId("team-2").name("T2").tenancyId("t1").build();
        registry.registerUnit(unit1);
        registry.registerUnit(unit2);

        service.createGroup("team-1", "t1", FormationOrigin.TOP_DOWN);
        service.createGroup("team-2", "t1", FormationOrigin.TOP_DOWN);

        var desired = loop.getDesired("t1");
        assertThat(desired.nodes()).containsKey(NodeId.of("team-1"));
        assertThat(desired.nodes()).containsKey(NodeId.of("team-2"));
    }

    @Test void joinGroupAddsDesiredMember() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-j").name("Join").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-j", "t1", FormationOrigin.TOP_DOWN);

        service.joinGroup("team-j", "agent-1", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-j").get();
        assertThat(unitGoal.desiredMembers()).containsExactly("agent-1");
    }

    @Test void leaveGroupRemovesDesiredMember() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-l").name("Leave").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-l", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-l", "agent-1", "t1");
        service.joinGroup("team-l", "agent-2", "t1");

        service.leaveGroup("team-l", "agent-1", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-l").get();
        assertThat(unitGoal.desiredMembers()).containsExactly("agent-2");
    }

    @Test void changeTopologyUpdatesUnitInOrgGoal() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-6").name("Team 6").tenancyId("t1")
            .topology("swarm").build();
        registry.registerUnit(unit);
        service.createGroup("team-6", "t1", FormationOrigin.TOP_DOWN);

        service.changeTopology("team-6", "mesh", null, "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-6").get();
        assertThat(unitGoal.unit().topology()).isEqualTo("mesh");
    }

    @Test void adoptRoleAddsDesiredRole() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-7").name("Team 7").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-7", "t1", FormationOrigin.TOP_DOWN);

        service.adoptRole("team-7", "agent-3", "coordinator", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-7").get();
        assertThat(unitGoal.desiredRoles()).anyMatch(
            r -> r.agentId().equals("agent-3") && r.roleName().equals("coordinator"));
    }

    @Test void releaseRoleRemovesDesiredRole() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-8").name("Team 8").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-8", "t1", FormationOrigin.TOP_DOWN);
        service.adoptRole("team-8", "agent-4", "analyst", "t1");

        service.releaseRole("team-8", "agent-4", "analyst", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-8").get();
        assertThat(unitGoal.desiredRoles()).noneMatch(
            r -> r.agentId().equals("agent-4") && r.roleName().equals("analyst"));
    }

    @Test void commitToMissionAddsDesiredMission() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-9").name("Team 9").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-9", "t1", FormationOrigin.TOP_DOWN);

        service.commitToMission("team-9", "agent-5", "investigate-fraud", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-9").get();
        assertThat(unitGoal.desiredMissions()).anyMatch(
            m -> m.agentId().equals("agent-5") && m.missionName().equals("investigate-fraud"));
    }

    @Test void fulfillMissionRemovesAllCommitmentsForMission() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-10").name("Team 10").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-10", "t1", FormationOrigin.TOP_DOWN);
        service.commitToMission("team-10", "agent-6", "deploy-fix", "t1");

        service.fulfillMission("team-10", "deploy-fix", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-10").get();
        assertThat(unitGoal.desiredMissions()).noneMatch(
            m -> m.missionName().equals("deploy-fix"));
    }

    @Test void mutationForUnknownGroupThrows() {
        assertThatThrownBy(() -> service.joinGroup("nonexistent", "agent-1", "t1"))
            .isInstanceOf(OrgValidationException.class);
    }

    @Test void dissolveGroupForMissingUnitThrows() {
        assertThatThrownBy(() -> service.dissolveGroup("nonexistent", "t1", "reason"))
            .isInstanceOf(OrgValidationException.class);
    }

    @Test void queryMethodsDelegateToInstanceStore() {
        assertThat(service.findInstance("missing", "t1")).isEmpty();
        assertThat(service.findInstances(new GroupInstanceQuery("t1", null, null, null))).isEmpty();
        assertThat(service.instancesForAgent("agent-1", "t1")).isEmpty();
    }
}
