package io.casehub.desiredstate.example.expansion;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.*;
import io.casehub.ras.api.ActiveSituation;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ExpansionLifecycleTest {

    private DesiredStateGraphFactory factory;
    private ExpansionGoalCompiler compiler;
    private ExpansionWorld world;
    private ExpansionProvisioner provisioner;
    private ExpansionActualStateAdapter adapter;
    private ReconciliationLoop loop;
    private LifecycleManager manager;

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        compiler = new ExpansionGoalCompiler();
        world = new ExpansionWorld();
        provisioner = new ExpansionProvisioner(world);
        adapter = new ExpansionActualStateAdapter(world);

        DefaultNodeProvisionerRouter router = new DefaultNodeProvisionerRouter(List.of(provisioner));
        SimpleTransitionExecutor executor = new SimpleTransitionExecutor(
            router, new NoOpHumanNodeHandler(), new NoOpPendingApprovalHandler());

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = new ReconciliationLoop(
            new TransitionPlanner(), executor, adapterRouter,
            new FaultPolicyEngine(List.of()),
            () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        manager = new LifecycleManager(loop);
    }

    @AfterEach
    void tearDown() {
        manager.stop("t1");
    }

    @Test
    void buildPhase_completesAndTransitionsToDefend() throws Exception {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus", "pylon", "cannon"), DefensePosture.PATROL);

        CompilationResult result = compiler.compile(goal, factory);
        assertThat(result).isInstanceOf(CompilationResult.Lifecycle.class);

        CompilationResult.Lifecycle lifecycle = (CompilationResult.Lifecycle) result;
        assertThat(lifecycle.phases()).hasSize(2);
        assertThat(lifecycle.phases().get(0).id()).isEqualTo("build");
        assertThat(lifecycle.phases().get(1).id()).isEqualTo("defend");

        manager.start("t1", result);

        // Wait for build phase to complete and defend phase to start
        Thread.sleep(1000);

        DesiredStateGraph current = loop.getDesired("t1");
        // Defend phase should be active — contains defense node types
        boolean hasDefenseNodes = current.nodes().values().stream()
            .anyMatch(n -> n.type().equals(ExpansionNodeTypes.PATROL)
                        || n.type().equals(ExpansionNodeTypes.MONITOR)
                        || n.type().equals(ExpansionNodeTypes.RESPONSE));
        assertThat(hasDefenseNodes).isTrue();
    }

    @Test
    void defendPhase_reconcilesContinuously() throws Exception {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus"), DefensePosture.PATROL);

        manager.start("t1", compiler.compile(goal, factory));
        Thread.sleep(500);

        // Destroy a defense node — reconciliation should re-provision
        NodeId patrolId = loop.getDesired("t1").nodes().keySet().stream()
            .filter(id -> loop.getDesired("t1").nodes().get(id).type().equals(ExpansionNodeTypes.PATROL))
            .findFirst().orElseThrow();
        world.destroy(patrolId);

        Thread.sleep(500);
        // Patrol provisioning sets PATROLLING state — check via adapter for PRESENT
        ActualState actual = adapter.readActual(loop.getDesired("t1"), "t1");
        assertThat(actual.statusOf(patrolId).orElseThrow()).isEqualTo(NodeStatus.PRESENT);
    }

    @Test
    void carryForward_defendPhaseReconcilesBuildArtifacts() throws Exception {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus"), DefensePosture.PATROL);

        manager.start("t1", compiler.compile(goal, factory));
        Thread.sleep(500);

        // Verify nexus is in defend phase graph (carry-forward)
        DesiredStateGraph current = loop.getDesired("t1");
        boolean hasNexus = current.nodes().values().stream()
            .anyMatch(n -> n.type().equals(ExpansionNodeTypes.NEXUS));
        assertThat(hasNexus).isTrue();

        // Destroy nexus — should be re-provisioned by defend phase reconciliation
        NodeId nexusId = current.nodes().keySet().stream()
            .filter(id -> current.nodes().get(id).type().equals(ExpansionNodeTypes.NEXUS))
            .findFirst().orElseThrow();
        world.destroy(nexusId);

        Thread.sleep(500);
        assertThat(world.isBuilt(nexusId)).isTrue();
    }

    @Test
    void faultTriggeredReplan_producesNewGraph() {
        ExpansionGoal goal = new ExpansionGoal("loc-1",
            List.of("nexus", "pylon"), DefensePosture.PATROL);

        ExpansionSituationRecompiler recompiler = new ExpansionSituationRecompiler(compiler, goal);

        // Build the initial graph
        CompilationResult initial = compiler.compile(goal, factory);
        DesiredStateGraph buildGraph = ((CompilationResult.Lifecycle) initial).phases().get(0).graph();

        // Simulate a situation — persistent nexus failure
        ActiveSituation situation = new ActiveSituation(
            "nexus-failure", "loc-1", "t1", 0.95,
            Map.of("failedNode", "nexus"), Instant.now(), Instant.now(), 5);

        Optional<CompilationResult> replanned = recompiler.recompile("tenant-1",
            buildGraph, new ActualState(Map.of()), situation, factory);

        assertThat(replanned).isPresent();
        // Replanned result should have FORTIFY defense posture
        CompilationResult.Lifecycle replanLifecycle = (CompilationResult.Lifecycle) replanned.get();
        assertThat(replanLifecycle.phases()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void singlePhaseBackwardCompat() throws Exception {
        // Compile a single-phase result (non-lifecycle)
        DesiredNode node = new DesiredNode(NodeId.of("standalone"),
            ExpansionNodeTypes.NEXUS, new NexusSpec("loc-1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        manager.start("t1", CompilationResult.single(graph));
        Thread.sleep(300);

        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("standalone"));
        assertThat(world.isBuilt(NodeId.of("standalone"))).isTrue();
    }
}
