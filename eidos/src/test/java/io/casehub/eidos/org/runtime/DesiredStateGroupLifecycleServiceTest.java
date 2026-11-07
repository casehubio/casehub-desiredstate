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
import io.casehub.eidos.org.api.FormationOrigin;
import io.casehub.eidos.org.api.FormationPath;
import io.casehub.eidos.org.api.FormationProposal;
import io.casehub.eidos.org.api.FormationRules;
import io.casehub.eidos.org.api.GroupInstance;
import io.casehub.eidos.org.api.GroupInstanceQuery;
import io.casehub.eidos.org.api.GroupLifecycleListener;
import io.casehub.eidos.org.api.GroupLifecycleState;
import io.casehub.eidos.org.api.GroupPolicy;
import io.casehub.eidos.org.api.OrgValidationException;
import io.casehub.eidos.org.api.OrganizationalUnit;
import io.casehub.eidos.org.api.RoleSpec;
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
        loop             = ReconciliationLoop.builder(
                                                     new TransitionPlanner(), new MockTransitionExecutor(), adapterRouter,
                                                     new FaultPolicyEngine(List.of()), () -> Multi.createFrom().nothing())
                                             .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofHours(1)).build();
        lifecycleManager = new LifecycleManager(loop);
        service          = new DesiredStateGroupLifecycleService(
                registry, instanceStore, goalStore, compiler, graphFactory, loop, lifecycleManager,
                new NoOpOrgChannelCreator());
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
        assertThat(instance.state()).isEqualTo(GroupLifecycleState.ACTIVE);
        assertThat(instance.formationOrigin()).isEqualTo(FormationOrigin.TOP_DOWN);
        var goal = goalStore.load("t1");
        assertThat(goal).isPresent();
        assertThat(goal.get().findUnitGoal("team-1")).isPresent();
        assertThat(loop.tenantIds()).contains("t1");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("team-1"));
    }

    @Test
    void proposalOriginReturnsProposedState() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-prop").name("Team Proposal").tenancyId("t1").build();
        registry.registerUnit(unit);
        var instance = service.createGroup("team-prop", "t1", FormationOrigin.PROPOSAL);
        assertThat(instance.state()).isEqualTo(GroupLifecycleState.PROPOSED);
    }

    @Test
    void emergenceOriginReturnsActiveState() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-em").name("Team Emergence").tenancyId("t1").build();
        registry.registerUnit(unit);
        var instance = service.createGroup("team-em", "t1", FormationOrigin.EMERGENCE);
        assertThat(instance.state()).isEqualTo(GroupLifecycleState.ACTIVE);
    }


    @Test void createGroupForMissingSpecThrows() {
        assertThatThrownBy(() -> service.createGroup("missing", "t1", FormationOrigin.TOP_DOWN))
            .isInstanceOf(OrgValidationException.class);
    }

    @Test void proposeGroupSetsProposalOrigin() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-p").name("Team P").tenancyId("t1").selfDetermination(0.5).build();
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

    @Test
    void dissolveGroupSetsInstanceToDissolving() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-dis").name("Team Dis").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-dis", "t1", FormationOrigin.TOP_DOWN);

        var gi = new GroupInstance("team-dis", "team-dis", "t1",
                                   GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
                                   null, null, null,
                                   List.of(new io.casehub.eidos.org.api.ActualMembership("agent-1", java.time.Instant.now())),
                                   List.of(), List.of(), java.time.Instant.now(), java.time.Instant.now(), 1);
        instanceStore.save(gi);

        service.dissolveGroup("team-dis", "t1", "winding down");

        var instance = instanceStore.findById("team-dis", "t1");
        assertThat(instance).isPresent();
        assertThat(instance.get().state()).isEqualTo(GroupLifecycleState.DISSOLVING);
    }

    @Test
    void dematerializeUnitFailsWhenMembersPresent() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-drain").name("Team Drain").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-drain", "t1", FormationOrigin.TOP_DOWN);

        var gi = new GroupInstance("team-drain", "team-drain", "t1",
                                   GroupLifecycleState.DISSOLVING, FormationOrigin.TOP_DOWN,
                                   null, null, null,
                                   List.of(new io.casehub.eidos.org.api.ActualMembership("agent-1", java.time.Instant.now())),
                                   List.of(), List.of(), java.time.Instant.now(), java.time.Instant.now(), 1);
        instanceStore.save(gi);

        var node = loop.getDesired("t1").nodes().get(NodeId.of("team-drain"));
        var ctx = new io.casehub.desiredstate.api.DeprovisionContext("t1",
                                                                     loop.getDesired("t1"), null);

        var result = service.dematerializeUnit(node, ctx);

        assertThat(result).isInstanceOf(io.casehub.desiredstate.api.DeprovisionResult.Failed.class);
    }

    @Test
    void dematerializeUnitSucceedsWhenMembersEmpty() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-empty").name("Team Empty").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-empty", "t1", FormationOrigin.TOP_DOWN);

        var gi = new GroupInstance("team-empty", "team-empty", "t1",
                                   GroupLifecycleState.DISSOLVING, FormationOrigin.TOP_DOWN,
                                   null, null, null,
                                   List.of(), List.of(), List.of(), java.time.Instant.now(), java.time.Instant.now(), 1);
        instanceStore.save(gi);

        var node = loop.getDesired("t1").nodes().get(NodeId.of("team-empty"));
        var ctx = new io.casehub.desiredstate.api.DeprovisionContext("t1",
                                                                     loop.getDesired("t1"), null);

        var result = service.dematerializeUnit(node, ctx);

        assertThat(result).isInstanceOf(io.casehub.desiredstate.api.DeprovisionResult.Success.class);
        var updated = instanceStore.findById("team-empty", "t1");
        assertThat(updated).isPresent();
        assertThat(updated.get().state()).isEqualTo(GroupLifecycleState.DISSOLVED);
    }

    @Test
    void materializeUnitPassesEnrichedChannelRequest() {
        var captured = new java.util.concurrent.atomic.AtomicReference<OrgChannelCreator.ChannelRequest>();
        var capturingCreator = new OrgChannelCreator() {
            @Override
            public boolean createGroupChannel(ChannelRequest request) {
                captured.set(request);
                return true;
            }
        };
        var captureService = new DesiredStateGroupLifecycleService(
                registry, instanceStore, goalStore, compiler, graphFactory, loop, lifecycleManager,
                capturingCreator);

        var unit = OrganizationalUnit.builder()
                                     .unitId("team-ch").name("Channel Team").tenancyId("t1")
                                     .members(List.of(
                                             new io.casehub.eidos.org.api.Membership("agent-1", "coordinator", null),
                                             new io.casehub.eidos.org.api.Membership("agent-2", "worker", null)))
                                     .build();
        registry.registerUnit(unit);
        captureService.createGroup("team-ch", "t1", FormationOrigin.TOP_DOWN);

        var node = loop.getDesired("t1").nodes().get(NodeId.of("team-ch"));
        var ctx = new io.casehub.desiredstate.api.ProvisionContext("t1",
                                                                   loop.getDesired("t1"), null);
        captureService.materializeUnit(node, ctx);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().groupName()).isEqualTo("Channel Team");
        assertThat(captured.get().memberIds()).containsExactlyInAnyOrder("agent-1", "agent-2");
        assertThat(captured.get().coordinatorId()).isEqualTo("agent-1");
        assertThat(captured.get().tenancyId()).isEqualTo("t1");
    }

    @Test
    void proposeGroupRejectsLowSelfDetermination() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-low").name("Low SD").tenancyId("t1")
                                     .selfDetermination(0.3).build();
        registry.registerUnit(unit);

        assertThatThrownBy(() -> service.proposeGroup("team-low", "t1", "agent-1"))
                .isInstanceOf(OrgValidationException.class)
                .hasMessageContaining("selfDetermination");
    }

    @Test
    void proposeGroupAllowsSufficientSelfDetermination() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-ok").name("OK SD").tenancyId("t1")
                                     .selfDetermination(0.5).build();
        registry.registerUnit(unit);

        var instance = service.proposeGroup("team-ok", "t1", "agent-1");

        assertThat(instance).isNotNull();
        assertThat(goalStore.load("t1").get().findUnitGoal("team-ok")).isPresent();
    }

    @Test
    void changeTopologyRejectsLowSelfDetermination() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-topo").name("Topo Team").tenancyId("t1")
                                     .topology("mesh").selfDetermination(0.5).build();
        registry.registerUnit(unit);
        service.createGroup("team-topo", "t1", FormationOrigin.TOP_DOWN);

        assertThatThrownBy(() -> service.changeTopology("team-topo", "swarm", null, "t1"))
                .isInstanceOf(OrgValidationException.class)
                .hasMessageContaining("selfDetermination");
    }

    @Test
    void changeTopologyAllowsHighSelfDetermination() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-topo2").name("Topo Team 2").tenancyId("t1")
                                     .topology("mesh").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-topo2", "t1", FormationOrigin.TOP_DOWN);

        service.changeTopology("team-topo2", "swarm", null, "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-topo2").get();
        assertThat(unitGoal.unit().topology()).isEqualTo("swarm");
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
            .topology("swarm").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-6", "t1", FormationOrigin.TOP_DOWN);

        service.changeTopology("team-6", "mesh", null, "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-6").get();
        assertThat(unitGoal.unit().topology()).isEqualTo("mesh");
    }

    @Test void adoptRoleAddsDesiredRole() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-7").name("Team 7").tenancyId("t1")
            .roleSpecs(List.of(new RoleSpec("coordinator", null, 0, -1, Set.of(), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-7", "t1", FormationOrigin.TOP_DOWN);

        service.adoptRole("team-7", "agent-3", "coordinator", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-7").get();
        assertThat(unitGoal.desiredRoles()).anyMatch(
            r -> r.agentId().equals("agent-3") && r.roleName().equals("coordinator"));
    }

    @Test void releaseRoleRemovesDesiredRole() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-8").name("Team 8").tenancyId("t1")
            .roleSpecs(List.of(new RoleSpec("analyst", null, 0, -1, Set.of(), List.of(), List.of(), List.of())))
            .build();
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

    // --- Constraint enforcement (#232) ---

    @Test void adoptRoleRejectsUnknownRole() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c1").name("C1").tenancyId("t1")
            .roleSpecs(List.of(new RoleSpec("leader", null, 0, 1, Set.of(), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-c1", "t1", FormationOrigin.TOP_DOWN);

        assertThatThrownBy(() -> service.adoptRole("team-c1", "agent-1", "nonexistent", "t1"))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("not defined");
    }

    @Test void adoptRoleRejectsWhenMaxCardinalityReached() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c2").name("C2").tenancyId("t1")
            .roleSpecs(List.of(new RoleSpec("leader", null, 0, 1, Set.of(), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-c2", "t1", FormationOrigin.TOP_DOWN);
        service.adoptRole("team-c2", "agent-1", "leader", "t1");

        assertThatThrownBy(() -> service.adoptRole("team-c2", "agent-2", "leader", "t1"))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("capacity");
    }

    @Test void adoptRoleAllowsUnlimitedCardinality() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c3").name("C3").tenancyId("t1")
            .roleSpecs(List.of(new RoleSpec("worker", null, 0, -1, Set.of(), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-c3", "t1", FormationOrigin.TOP_DOWN);
        service.adoptRole("team-c3", "agent-1", "worker", "t1");
        service.adoptRole("team-c3", "agent-2", "worker", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-c3").get();
        assertThat(unitGoal.desiredRoles()).hasSize(2);
    }

    @Test void adoptRoleRejectsIncompatibleRoles() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c4").name("C4").tenancyId("t1")
            .roleSpecs(List.of(
                new RoleSpec("leader", null, 0, -1, Set.of("auditor"), List.of(), List.of(), List.of()),
                new RoleSpec("auditor", null, 0, -1, Set.of("leader"), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-c4", "t1", FormationOrigin.TOP_DOWN);
        service.adoptRole("team-c4", "agent-1", "leader", "t1");

        assertThatThrownBy(() -> service.adoptRole("team-c4", "agent-1", "auditor", "t1"))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("incompatible");
    }

    @Test void adoptRoleAllowsCompatibleRolesForSameAgent() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c5").name("C5").tenancyId("t1")
            .roleSpecs(List.of(
                new RoleSpec("leader", null, 0, -1, Set.of(), List.of(), List.of(), List.of()),
                new RoleSpec("scribe", null, 0, -1, Set.of(), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-c5", "t1", FormationOrigin.TOP_DOWN);
        service.adoptRole("team-c5", "agent-1", "leader", "t1");
        service.adoptRole("team-c5", "agent-1", "scribe", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-c5").get();
        assertThat(unitGoal.desiredRoles()).hasSize(2);
    }

    @Test void joinGroupRejectsWhenMaxGroupSizeReached() {
        var rules = new FormationRules(Set.of(FormationPath.TOP_DOWN), 1, 2, false, 1.0);
        var policy = new GroupPolicy(rules, 0, null, 30);
        var unit = OrganizationalUnit.builder()
            .unitId("team-c6").name("C6").tenancyId("t1").policy(policy).build();
        registry.registerUnit(unit);
        service.createGroup("team-c6", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-c6", "agent-1", "t1");
        service.joinGroup("team-c6", "agent-2", "t1");

        assertThatThrownBy(() -> service.joinGroup("team-c6", "agent-3", "t1"))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("maxGroupSize");
    }

    @Test void joinGroupAllowsUnlimitedSize() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c7").name("C7").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-c7", "t1", FormationOrigin.TOP_DOWN);
        service.joinGroup("team-c7", "agent-1", "t1");
        service.joinGroup("team-c7", "agent-2", "t1");
        service.joinGroup("team-c7", "agent-3", "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-c7").get();
        assertThat(unitGoal.desiredMembers()).hasSize(3);
    }

    @Test void createGroupRejectsDisallowedFormationPath() {
        var rules = new FormationRules(Set.of(FormationPath.EMERGENCE), 1, -1, false, 1.0);
        var policy = new GroupPolicy(rules, 0, null, 30);
        var unit = OrganizationalUnit.builder()
            .unitId("team-c8").name("C8").tenancyId("t1").policy(policy).build();
        registry.registerUnit(unit);

        assertThatThrownBy(() -> service.createGroup("team-c8", "t1", FormationOrigin.TOP_DOWN))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("allowedPaths");
    }

    @Test void createGroupAllowsMatchingFormationPath() {
        var rules = new FormationRules(Set.of(FormationPath.TOP_DOWN), 1, -1, false, 1.0);
        var policy = new GroupPolicy(rules, 0, null, 30);
        var unit = OrganizationalUnit.builder()
            .unitId("team-c9").name("C9").tenancyId("t1").policy(policy).build();
        registry.registerUnit(unit);

        var result = service.createGroup("team-c9", "t1", FormationOrigin.TOP_DOWN);
        assertThat(result).isNotNull();
    }

    @Test void adoptRoleWithNoRoleSpecsRejects() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-c10").name("C10").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-c10", "t1", FormationOrigin.TOP_DOWN);

        assertThatThrownBy(() -> service.adoptRole("team-c10", "agent-1", "leader", "t1"))
            .isInstanceOf(OrgValidationException.class)
            .hasMessageContaining("not defined");
    }

    // --- Lifecycle listener events (#233) ---

    @Test void createGroupFiresOnFormationProposal() {
        var captured = new java.util.ArrayList<String>();
        var listener = new GroupLifecycleListener() {
            @Override public void onFormationProposal(FormationProposal proposal) {
                captured.add("proposal:" + proposal.specId());
            }
        };
        service = createServiceWithListeners(List.of(listener));
        var unit = OrganizationalUnit.builder()
            .unitId("team-e1").name("E1").tenancyId("t1").build();
        registry.registerUnit(unit);

        service.createGroup("team-e1", "t1", FormationOrigin.TOP_DOWN);

        assertThat(captured).containsExactly("proposal:team-e1");
    }

    @Test void dissolveGroupFiresOnDissolution() {
        var captured = new java.util.ArrayList<String>();
        var listener = new GroupLifecycleListener() {
            @Override public void onDissolution(String instanceId, String tenancyId, String reason) {
                captured.add("dissolve:" + instanceId);
            }
        };
        service = createServiceWithListeners(List.of(listener));
        var unit = OrganizationalUnit.builder()
            .unitId("team-e2").name("E2").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-e2", "t1", FormationOrigin.TOP_DOWN);

        service.dissolveGroup("team-e2", "t1", "done");

        assertThat(captured).contains("dissolve:team-e2");
    }

    @Test void joinGroupFiresOnMemberJoined() {
        var captured = new java.util.ArrayList<String>();
        var listener = new GroupLifecycleListener() {
            @Override public void onMemberJoined(String instanceId, String agentId, String tenancyId) {
                captured.add("joined:" + agentId);
            }
        };
        service = createServiceWithListeners(List.of(listener));
        var unit = OrganizationalUnit.builder()
            .unitId("team-e3").name("E3").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-e3", "t1", FormationOrigin.TOP_DOWN);

        service.joinGroup("team-e3", "agent-1", "t1");

        assertThat(captured).containsExactly("joined:agent-1");
    }

    @Test void adoptRoleFiresOnRoleAdoption() {
        var captured = new java.util.ArrayList<String>();
        var listener = new GroupLifecycleListener() {
            @Override public void onRoleAdoption(String instanceId, String agentId, String roleName, String tenancyId) {
                captured.add("adopt:" + agentId + ":" + roleName);
            }
        };
        service = createServiceWithListeners(List.of(listener));
        var unit = OrganizationalUnit.builder()
            .unitId("team-e4").name("E4").tenancyId("t1")
            .roleSpecs(List.of(new RoleSpec("leader", null, 0, -1, Set.of(), List.of(), List.of(), List.of())))
            .build();
        registry.registerUnit(unit);
        service.createGroup("team-e4", "t1", FormationOrigin.TOP_DOWN);

        service.adoptRole("team-e4", "agent-1", "leader", "t1");

        assertThat(captured).containsExactly("adopt:agent-1:leader");
    }

    @Test void changeTopologyFiresOnTopologyChange() {
        var captured = new java.util.ArrayList<String>();
        var listener = new GroupLifecycleListener() {
            @Override public void onTopologyChange(String instanceId, String oldTopology, String newTopology, String tenancyId) {
                captured.add("topo:" + oldTopology + "->" + newTopology);
            }
        };
        service = createServiceWithListeners(List.of(listener));
        var unit = OrganizationalUnit.builder()
            .unitId("team-e5").name("E5").tenancyId("t1").topology("swarm").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-e5", "t1", FormationOrigin.TOP_DOWN);

        service.changeTopology("team-e5", "mesh", null, "t1");

        assertThat(captured).containsExactly("topo:swarm->mesh");
    }


// --- REORGANISING state (#236) ---

    @Test
    void changeTopologySetsReorganisingWhenInstanceActive() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-r1").name("R1").tenancyId("t1").topology("swarm").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-r1", "t1", FormationOrigin.TOP_DOWN);
        seedActiveInstance("team-r1", "t1");

        service.changeTopology("team-r1", "mesh", null, "t1");

        var gi = instanceStore.findById("team-r1", "t1").orElseThrow();
        assertThat(gi.state()).isEqualTo(GroupLifecycleState.REORGANISING);
    }

    @Test
    void changeTopologyUpdatesGoalEvenDuringReorganising() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-r2").name("R2").tenancyId("t1").topology("swarm").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-r2", "t1", FormationOrigin.TOP_DOWN);
        seedActiveInstance("team-r2", "t1");

        service.changeTopology("team-r2", "mesh", null, "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-r2").get();
        assertThat(unitGoal.unit().topology()).isEqualTo("mesh");
    }

    @Test
    void concurrentMutationBlockedWhileReorganising() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-r3").name("R3").tenancyId("t1").topology("swarm").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-r3", "t1", FormationOrigin.TOP_DOWN);
        seedActiveInstance("team-r3", "t1");

        service.changeTopology("team-r3", "mesh", null, "t1");

        assertThatThrownBy(() -> service.joinGroup("team-r3", "agent-1", "t1"))
                .isInstanceOf(OrgValidationException.class)
                .hasMessageContaining("reorganising");
    }

    @Test
    void changeTopologyWorksWhenNoInstanceExists() {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-r4").name("R4").tenancyId("t1").topology("swarm").selfDetermination(0.8).build();
        registry.registerUnit(unit);
        service.createGroup("team-r4", "t1", FormationOrigin.TOP_DOWN);

        service.changeTopology("team-r4", "mesh", null, "t1");

        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-r4").get();
        assertThat(unitGoal.unit().topology()).isEqualTo("mesh");
    }


// --- Per-society locking (#237) ---

    @Test
    void concurrentJoinsAreSerialized() throws Exception {
        var unit = OrganizationalUnit.builder()
                                     .unitId("team-l1").name("L1").tenancyId("t1").build();
        registry.registerUnit(unit);
        service.createGroup("team-l1", "t1", FormationOrigin.TOP_DOWN);

        var threads = new java.util.ArrayList<Thread>();
        var errors  = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        for (int i = 0; i < 5; i++) {
            final String agentId = "agent-" + i;
            var t = new Thread(() -> {
                try {
                    service.joinGroup("team-l1", agentId, "t1");
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            threads.add(t);
        }
        threads.forEach(Thread::start);
        for (var t : threads) {
            t.join(5000);
        }

        assertThat(errors).isEmpty();
        var unitGoal = goalStore.load("t1").get().findUnitGoal("team-l1").get();
        assertThat(unitGoal.desiredMembers()).hasSize(5);
    }

    @Test
    void lockIsPerUnit() {
        var unitA = OrganizationalUnit.builder()
                                      .unitId("team-la").name("LA").tenancyId("t1").build();
        var unitB = OrganizationalUnit.builder()
                                      .unitId("team-lb").name("LB").tenancyId("t1").build();
        registry.registerUnit(unitA);
        registry.registerUnit(unitB);
        service.createGroup("team-la", "t1", FormationOrigin.TOP_DOWN);
        service.createGroup("team-lb", "t1", FormationOrigin.TOP_DOWN);

        service.joinGroup("team-la", "agent-1", "t1");
        service.joinGroup("team-lb", "agent-2", "t1");

        var goalA = goalStore.load("t1").get().findUnitGoal("team-la").get();
        var goalB = goalStore.load("t1").get().findUnitGoal("team-lb").get();
        assertThat(goalA.desiredMembers()).containsExactly("agent-1");
        assertThat(goalB.desiredMembers()).containsExactly("agent-2");
    }

    private void seedActiveInstance(String unitId, String tenancyId) {
        var now = java.time.Instant.now();
        instanceStore.save(new GroupInstance(
                unitId, unitId, tenancyId,
                GroupLifecycleState.ACTIVE, FormationOrigin.TOP_DOWN,
                null, null, null, List.of(), List.of(), List.of(),
                now, now, 0));
    }

    private DesiredStateGroupLifecycleService createServiceWithListeners(List<GroupLifecycleListener> listeners) {
        return new DesiredStateGroupLifecycleService(
                registry, instanceStore, goalStore, compiler, graphFactory, loop, lifecycleManager,
                new NoOpOrgChannelCreator(), listeners);
    }
}
