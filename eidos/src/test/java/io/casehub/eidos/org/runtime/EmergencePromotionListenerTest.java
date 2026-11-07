package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.DesiredStateGraphFactory;
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

class EmergencePromotionListenerTest {

    private final DesiredStateOrgAdapterTest.InMemoryOrgRegistry registry =
        new DesiredStateOrgAdapterTest.InMemoryOrgRegistry();
    private final DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore instanceStore =
        new DesiredStateOrgAdapterTest.InMemoryGroupInstanceStore();
    private final InMemoryOrgGoalStore goalStore = new InMemoryOrgGoalStore();
    private final OrgGoalCompiler compiler = new OrgGoalCompiler();
    private final DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

    private ReconciliationLoop loop;
    private DesiredStateGroupLifecycleService lifecycleService;
    private EmergencePromotionListener listener;

    @BeforeEach
    void setUp() {
        var adapter = new MockActualStateAdapter();
        adapter.setHandledTypes(Set.of(OrgNodeTypes.UNIT, OrgNodeTypes.RELATIONSHIP));
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = ReconciliationLoop.builder(
            new TransitionPlanner(), new MockTransitionExecutor(), adapterRouter,
            new FaultPolicyEngine(List.of()), () -> Multi.createFrom().nothing())
            .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofHours(1)).build();
        var lifecycleManager = new LifecycleManager(loop);
        lifecycleService = new DesiredStateGroupLifecycleService(
            registry, instanceStore, goalStore, compiler, graphFactory, loop, lifecycleManager,
            new NoOpOrgChannelCreator());
        listener = new EmergencePromotionListener(lifecycleService);
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    @Test void promotesSwarmTeamToGroup() {
        var data = new SwarmTeamFormedData(
            "detected-team-1", "t1",
            List.of("agent-a", "agent-b"), "COACTIVE");

        listener.promote(data);

        assertThat(registry.findUnit("detected-team-1", "t1")).isPresent();
        assertThat(goalStore.load("t1")).isPresent();
        assertThat(goalStore.load("t1").get().findUnitGoal("detected-team-1")).isPresent();
    }

    @Test void coactiveRelationDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-swarm", "t1", List.of("a1"), "COACTIVE");

        listener.promote(data);

        var unit = registry.findUnit("team-swarm", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void complementaryRelationDefaultsToHiveTopology() {
        var data = new SwarmTeamFormedData(
            "team-hive", "t1", List.of("a1"), "COMPLEMENTARY");

        listener.promote(data);

        var unit = registry.findUnit("team-hive", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("hive");
    }

    @Test void sharedInterestDefaultsToMeshTopology() {
        var data = new SwarmTeamFormedData(
            "team-mesh", "t1", List.of("a1"), "SHARED_INTEREST");

        listener.promote(data);

        var unit = registry.findUnit("team-mesh", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("mesh");
    }

    @Test void sharedSignalDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-signal", "t1", List.of("a1"), "SHARED_SIGNAL");

        listener.promote(data);

        var unit = registry.findUnit("team-signal", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void unknownRelationDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-unknown", "t1", List.of("a1"), "SOMETHING_ELSE");

        listener.promote(data);

        var unit = registry.findUnit("team-unknown", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void nullRelationDefaultsToSwarmTopology() {
        var data = new SwarmTeamFormedData(
            "team-null", "t1", List.of("a1"), null);

        listener.promote(data);

        var unit = registry.findUnit("team-null", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().topology()).isEqualTo("swarm");
    }

    @Test void membersAreSeededInUnit() {
        var data = new SwarmTeamFormedData(
            "team-members", "t1",
            List.of("agent-x", "agent-y", "agent-z"), "COACTIVE");

        listener.promote(data);

        var unit = registry.findUnit("team-members", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().members()).hasSize(3);
        assertThat(unit.get().members().stream().map(Membership::agentId))
            .containsExactlyInAnyOrder("agent-x", "agent-y", "agent-z");
    }

    @Test void formationOriginIsEmergence() {
        var data = new SwarmTeamFormedData(
            "team-emerge", "t1", List.of("a1"), "COACTIVE");

        listener.promote(data);

        assertThat(goalStore.load("t1").get().findUnitGoal("team-emerge")).isPresent();
    }

    @Test void selfDeterminationDefaultsToMidRange() {
        var data = new SwarmTeamFormedData(
            "team-sd", "t1", List.of("a1"), "COACTIVE");

        listener.promote(data);

        var unit = registry.findUnit("team-sd", "t1");
        assertThat(unit).isPresent();
        assertThat(unit.get().selfDetermination()).isEqualTo(0.5);
    }
}
