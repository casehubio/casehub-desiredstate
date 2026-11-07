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
import io.casehub.eidos.org.api.spi.OrgRegistrar;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrgBootstrapTest {

    private final DesiredStateOrgAdapterTest.InMemoryOrgRegistry registry =
        new DesiredStateOrgAdapterTest.InMemoryOrgRegistry();
    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final InMemoryOrgGoalStore goalStore = new InMemoryOrgGoalStore();
    private final OrgGoalCompiler compiler = new OrgGoalCompiler();
    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    private ReconciliationLoop loop;
    private LifecycleManager lifecycleManager;

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
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    @Test void freshBootBuildsOrgGoalFromRegistrarsAndStartsLoop() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1")
            .selfDetermination(0.5).build();
        var registrar = registrarOf(List.of(unit), List.of());

        var bootstrap = new OrgBootstrap(registry, List.of(registrar),
            goalStore, compiler, graphFactory, lifecycleManager);
        bootstrap.onStartup();

        assertThat(loop.tenantIds()).contains("t1");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("team-1"));
        assertThat(goalStore.load("t1")).isPresent();
        assertThat(goalStore.load("t1").get().findUnitGoal("team-1")).isPresent();
        assertThat(registry.findUnit("team-1", "t1")).isPresent();
    }

    @Test void freshBootIncludesRelationshipsInGoal() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-r").name("Team R").tenancyId("t1").build();
        var rel = AgentRelationship.builder()
            .sourceAgentId("a").targetAgentId("b")
            .kind(RelationshipKind.SUPERVISES).tenancyId("t1").build();
        var registrar = registrarOf(List.of(unit), List.of(rel));

        var bootstrap = new OrgBootstrap(registry, List.of(registrar),
            goalStore, compiler, graphFactory, lifecycleManager);
        bootstrap.onStartup();

        var desired = loop.getDesired("t1");
        assertThat(desired.nodes()).containsKey(NodeId.of("rel:a:b:SUPERVISES"));
        assertThat(goalStore.load("t1").get().relationships()).hasSize(1);
    }

    @Test void restartUsesPersistedGoalOverRegistrarDefinitions() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1").build();
        var registrar = registrarOf(List.of(unit), List.of());

        var persistedGoal = OrgGoal.empty()
            .withUnitGoal(new OrgUnitGoal(unit).withMember("agent-x"));
        goalStore.store("t1", persistedGoal);

        var bootstrap = new OrgBootstrap(registry, List.of(registrar),
            goalStore, compiler, graphFactory, lifecycleManager);
        bootstrap.onStartup();

        var goal = goalStore.load("t1").orElseThrow();
        assertThat(goal.findUnitGoal("team-1").get().desiredMembers())
            .containsExactly("agent-x");
    }

    @Test void multipleRegistrarsAcrossTenancies() {
        var unitA = OrganizationalUnit.builder()
            .unitId("team-a").name("A").tenancyId("t1").build();
        var unitB = OrganizationalUnit.builder()
            .unitId("team-b").name("B").tenancyId("t2").build();
        var r1 = registrarOf(List.of(unitA), List.of());
        var r2 = registrarOf(List.of(unitB), List.of());

        var bootstrap = new OrgBootstrap(registry, List.of(r1, r2),
            goalStore, compiler, graphFactory, lifecycleManager);
        bootstrap.onStartup();

        assertThat(loop.tenantIds()).containsExactlyInAnyOrder("t1", "t2");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("team-a"));
        assertThat(loop.getDesired("t2").nodes()).containsKey(NodeId.of("team-b"));
    }

    @Test void noRegistrarsNoLoopStarted() {
        var bootstrap = new OrgBootstrap(registry, List.of(),
            goalStore, compiler, graphFactory, lifecycleManager);
        bootstrap.onStartup();

        assertThat(loop.tenantIds()).isEmpty();
    }

    @Test void registersUnitsInRegistryEvenOnRestart() {
        var unit = OrganizationalUnit.builder()
            .unitId("team-1").name("Team 1").tenancyId("t1").build();
        var registrar = registrarOf(List.of(unit), List.of());
        goalStore.store("t1", OrgGoal.empty().withUnitGoal(new OrgUnitGoal(unit)));

        var bootstrap = new OrgBootstrap(registry, List.of(registrar),
            goalStore, compiler, graphFactory, lifecycleManager);
        bootstrap.onStartup();

        assertThat(registry.findUnit("team-1", "t1")).isPresent();
    }

    private static OrgRegistrar registrarOf(List<OrganizationalUnit> units,
                                             List<AgentRelationship> rels) {
        return () -> new OrgRegistrar.OrgDefinition(units, rels);
    }
}
