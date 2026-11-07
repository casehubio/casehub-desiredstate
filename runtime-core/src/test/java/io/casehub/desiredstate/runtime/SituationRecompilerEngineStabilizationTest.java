package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class SituationRecompilerEngineStabilizationTest {

    private record TestSpec(String value) implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("test"); }
    }

    private final DesiredStateGraph graph = ImmutableDesiredStateGraph.empty();
    private final ActualState actual = new ActualState(Map.of());
    private final ActiveSituation situation1 = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);
    private final ActiveSituation situation2 = new ActiveSituation(
        "sit-2", "zone-B", "tenant-1", 0.8,
        Map.of(), Instant.now().minusSeconds(30), Instant.now(), 1);

    private DesiredStateGraph recompiledGraph;
    private List<String> recompileCalls;
    private List<SituationRecompilerEngine.QueuedSituation> drainedSituations;
    private SituationRecompiler recompiler;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        recompileCalls = new ArrayList<>();
        drainedSituations = new ArrayList<>();
        recompiledGraph = graph.withNode(
            new DesiredNode(NodeId.of("n1"), new TestSpec("v1"), HumanGating.NONE));
        recompiler = (tid, c, a, s, f) -> {
            recompileCalls.add(tid + ":" + s.situationId());
            return Optional.of(CompilationResult.single(recompiledGraph));
        };
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void disabledStabilization_shouldNotQueue() {
        var engine = new SituationRecompilerEngine(List.of(recompiler),
            Duration.ZERO, scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        var result1 = engine.recompile("tenant-1", graph, actual, situation1, null);
        assertThat(result1).isPresent();

        var result2 = engine.recompile("tenant-1", graph, actual, situation2, null);
        assertThat(result2).isPresent();

        assertThat(recompileCalls).containsExactly("tenant-1:sit-1", "tenant-1:sit-2");
    }

    @Test
    void backwardCompatibleConstructor_shouldDisableStabilization() {
        var engine = new SituationRecompilerEngine(List.of(recompiler));

        var result1 = engine.recompile("tenant-1", graph, actual, situation1, null);
        var result2 = engine.recompile("tenant-1", graph, actual, situation2, null);

        assertThat(result1).isPresent();
        assertThat(result2).isPresent();
        assertThat(recompileCalls).hasSize(2);
    }

    @Test
    void enabledStabilization_shouldQueueSecondSituation() {
        var engine = new SituationRecompilerEngine(List.of(recompiler),
            Duration.ofMinutes(5), scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        var result1 = engine.recompile("tenant-1", graph, actual, situation1, null);
        assertThat(result1).isPresent();
        assertThat(recompileCalls).containsExactly("tenant-1:sit-1");

        var result2 = engine.recompile("tenant-1", graph, actual, situation2, null);
        assertThat(result2).isEmpty();
        assertThat(recompileCalls).hasSize(1);
    }

    @Test
    void differentTenant_shouldNotBeBlocked() {
        var engine = new SituationRecompilerEngine(List.of(recompiler),
            Duration.ofMinutes(5), scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        engine.recompile("tenant-1", graph, actual, situation1, null);

        var tenantBSituation = new ActiveSituation(
            "sit-3", "zone-C", "tenant-2", 0.7,
            Map.of(), Instant.now().minusSeconds(10), Instant.now(), 1);
        var result = engine.recompile("tenant-2", graph, actual, tenantBSituation, null);

        assertThat(result).isPresent();
        assertThat(recompileCalls).containsExactly("tenant-1:sit-1", "tenant-2:sit-3");
    }

    @Test
    void noRecompilation_shouldNotStartWindow() {
        SituationRecompiler empty = (tid, c, a, s, f) -> Optional.empty();
        var engine = new SituationRecompilerEngine(List.of(empty),
            Duration.ofMinutes(5), scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        engine.recompile("tenant-1", graph, actual, situation1, null);
        var result2 = engine.recompile("tenant-1", graph, actual, situation2, null);

        assertThat(result2).isEmpty();
        assertThat(drainedSituations).isEmpty();
    }

    @Test
    void situationResolved_shouldAlsoBeGated() {
        var engine = new SituationRecompilerEngine(List.of(recompiler),
            Duration.ofMinutes(5), scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        engine.recompile("tenant-1", graph, actual, situation1, null);

        var resolved = engine.situationResolved("tenant-1", "sit-1", graph, actual, null);
        assertThat(resolved).isEmpty();
    }

    @Test
    void clearStabilization_shouldAllowNextRecompile() {
        var engine = new SituationRecompilerEngine(List.of(recompiler),
            Duration.ofMinutes(5), scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        engine.recompile("tenant-1", graph, actual, situation1, null);

        engine.clearStabilization("tenant-1");

        var result2 = engine.recompile("tenant-1", graph, actual, situation2, null);
        assertThat(result2).isPresent();
        assertThat(recompileCalls).hasSize(2);
    }

    @Test
    void drain_shouldFireAfterWindowExpiry() throws InterruptedException {
        var latch = new java.util.concurrent.CountDownLatch(1);
        var engine = new SituationRecompilerEngine(List.of(recompiler),
                                                   Duration.ofMillis(100), scheduler, (tid, queued) -> {
            drainedSituations.addAll(queued);
            latch.countDown();
        });

        engine.recompile("tenant-1", graph, actual, situation1, null);
        engine.recompile("tenant-1", graph, actual, situation2, null);

        assertThat(drainedSituations).isEmpty();

        assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(drainedSituations).hasSize(1);
        assertThat(drainedSituations.getFirst()).isInstanceOf(
                SituationRecompilerEngine.QueuedSituation.Recompile.class);
        var recompileQueued = (SituationRecompilerEngine.QueuedSituation.Recompile) drainedSituations.getFirst();
        assertThat(recompileQueued.situation().situationId()).isEqualTo("sit-2");
    }

    @Test
    void situationResolved_queuedAndDrained() throws InterruptedException {
        var latch = new java.util.concurrent.CountDownLatch(1);
        SituationRecompiler withResolve = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(String tid, DesiredStateGraph c, ActualState a,
                                                         ActiveSituation s, io.casehub.desiredstate.api.DesiredStateGraphFactory f) {
                recompileCalls.add(tid + ":" + s.situationId());
                return Optional.of(CompilationResult.single(recompiledGraph));
            }

            @Override
            public Optional<CompilationResult> situationResolved(String tid, String sitId,
                                                                 DesiredStateGraph c, ActualState a, io.casehub.desiredstate.api.DesiredStateGraphFactory f) {
                return Optional.of(CompilationResult.single(graph));
            }
        };

        var engine = new SituationRecompilerEngine(List.of(withResolve),
                                                   Duration.ofMillis(100), scheduler, (tid, queued) -> {
            drainedSituations.addAll(queued);
            latch.countDown();
        });

        engine.recompile("tenant-1", graph, actual, situation1, null);
        engine.situationResolved("tenant-1", "sit-1", graph, actual, null);

        assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(drainedSituations).hasSize(1);
        assertThat(drainedSituations.getFirst()).isInstanceOf(
                SituationRecompilerEngine.QueuedSituation.Resolved.class);
    }

    @Test
    void recompileImmediate_shouldBypassStabilizationWindow() {
        var engine = new SituationRecompilerEngine(List.of(recompiler),
                                                   Duration.ofMinutes(5), scheduler, (tid, queued) -> drainedSituations.addAll(queued));

        engine.recompile("tenant-1", graph, actual, situation1, null);

        var result = engine.recompileImmediate("tenant-1", graph, actual, situation2, null);
        assertThat(result).isPresent();
        assertThat(recompileCalls).containsExactly("tenant-1:sit-1", "tenant-1:sit-2");
    }
}
