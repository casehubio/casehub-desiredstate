package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.MergedEventSource;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationChangeEvent;
import io.casehub.ras.api.SituationContext;
import io.casehub.ras.api.SituationSource;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SituationRecompilerDispatchFullTest {

    private record TestSpec(String value) implements NodeSpec {
        @Override
        public NodeType nodeType() { return NodeType.of("test"); }
    }

    private SituationRecompilerDispatch dispatch;
    private ReconciliationLoop loop;
    private LifecycleManager lifecycleManager;
    private List<String> recompileCalls;
    private List<String> resolvedCalls;
    private DesiredStateGraph baseGraph;
    private DesiredStateGraph recompiledGraph;
    private boolean recompilerReturnsResult;
    private boolean resolvedReturnsResult;
    private List<ActiveSituation> activeSituations;

    @BeforeEach
    void setUp() throws Exception {
        recompileCalls = new ArrayList<>();
        resolvedCalls = new ArrayList<>();
        recompilerReturnsResult = true;
        resolvedReturnsResult = true;
        activeSituations = new ArrayList<>();

        baseGraph = ImmutableDesiredStateGraph.empty().withNode(
                new DesiredNode(NodeId.of("existing"), new TestSpec("base"), HumanGating.NONE));
        recompiledGraph = baseGraph.withNode(
                new DesiredNode(NodeId.of("adapted"), new TestSpec("adapted"), HumanGating.NONE));

        SituationRecompiler recompiler = new SituationRecompiler() {
            @Override
            public Optional<CompilationResult> recompile(String tid, DesiredStateGraph c, ActualState a,
                    ActiveSituation s, DesiredStateGraphFactory f) {
                recompileCalls.add(tid + ":" + s.situationId());
                return recompilerReturnsResult
                        ? Optional.of(CompilationResult.single(recompiledGraph))
                        : Optional.empty();
            }
            @Override
            public Optional<CompilationResult> situationResolved(String tid, String sitId,
                    DesiredStateGraph c, ActualState a, DesiredStateGraphFactory f) {
                resolvedCalls.add(tid + ":" + sitId);
                return resolvedReturnsResult
                        ? Optional.of(CompilationResult.single(baseGraph))
                        : Optional.empty();
            }
        };

        SituationRecompilerEngine engine = new SituationRecompilerEngine(List.of(recompiler));

        ActualStateAdapterRouter actualRouter = new ActualStateAdapterRouter() {
            @Override
            public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                return new ActualState(Map.of());
            }
            @Override
            public Set<NodeType> allHandledTypes() { return Set.of(); }
        };

        TransitionPlanner planner = new TransitionPlanner();
        SimpleTransitionExecutor executor = new SimpleTransitionExecutor(
                new NodeStepExecutor(new DefaultNodeProvisionerRouter(List.of()), new NoOpHumanNodeHandler(),
                        new NoOpPendingApprovalHandler(), null));
        MergedEventSource eventSource = () -> Multi.createFrom().empty();
        FaultPolicyEngine faultPolicyEngine = new FaultPolicyEngine(List.of());

        loop = new ReconciliationLoop(planner, executor, actualRouter, faultPolicyEngine, eventSource);

        lifecycleManager = new LifecycleManager(loop);

        SituationSource situationSource = tenancyId -> activeSituations;

        DesiredStateGraphFactory graphFactory = new DefaultDesiredStateGraphFactory();

        dispatch = new SituationRecompilerDispatch();
        setField(dispatch, "engine", engine);
        setField(dispatch, "lifecycleManager", lifecycleManager);
        setField(dispatch, "reconciliationLoop", loop);
        setField(dispatch, "actualStateRouter", actualRouter);
        setField(dispatch, "graphFactory", graphFactory);
        setField(dispatch, "situationSource", situationSource);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    // --- onSituationChange: TRIGGERED ---

    @Test
    void triggered_callsRecompileAndUpdatesGraph() {
        loop.start("t1", baseGraph);

        SituationChangeEvent event = triggeredEvent("t1", "sit-1");
        dispatch.onSituationChange(event);

        assertThat(recompileCalls).containsExactly("t1:sit-1");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("adapted"));
    }

    @Test
    void triggered_emptyResult_doesNotUpdateGraph() {
        loop.start("t1", baseGraph);
        recompilerReturnsResult = false;

        SituationChangeEvent event = triggeredEvent("t1", "sit-1");
        dispatch.onSituationChange(event);

        assertThat(recompileCalls).containsExactly("t1:sit-1");
        assertThat(loop.getDesired("t1").nodes()).doesNotContainKey(NodeId.of("adapted"));
    }

    @Test
    void triggered_unknownTenant_isIgnored() {
        SituationChangeEvent event = triggeredEvent("unknown-tenant", "sit-1");
        dispatch.onSituationChange(event);

        assertThat(recompileCalls).isEmpty();
    }

    // --- onSituationChange: RESOLVED ---

    @Test
    void resolved_callsSituationResolvedAndUpdatesGraph() {
        loop.start("t1", recompiledGraph);

        SituationChangeEvent event = resolvedEvent("t1", "sit-1");
        dispatch.onSituationChange(event);

        assertThat(resolvedCalls).containsExactly("t1:sit-1");
        assertThat(loop.getDesired("t1")).isEqualTo(baseGraph);
    }

    @Test
    void resolved_emptyResult_doesNotUpdateGraph() {
        loop.start("t1", recompiledGraph);
        resolvedReturnsResult = false;

        SituationChangeEvent event = resolvedEvent("t1", "sit-1");
        dispatch.onSituationChange(event);

        assertThat(resolvedCalls).containsExactly("t1:sit-1");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("adapted"));
    }

    @Test
    void resolved_unknownTenant_isIgnored() {
        SituationChangeEvent event = resolvedEvent("unknown-tenant", "sit-1");
        dispatch.onSituationChange(event);

        assertThat(resolvedCalls).isEmpty();
    }

    // --- onColdStart ---

    @Test
    void coldStart_replaysActiveSituations() {
        loop.start("t1", baseGraph);
        activeSituations.add(new ActiveSituation("sit-1", "key-1", "t1", 0.9,
                Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3));

        dispatch.onColdStart(null);

        assertThat(recompileCalls).containsExactly("t1:sit-1");
        assertThat(loop.getDesired("t1").nodes()).containsKey(NodeId.of("adapted"));
    }

    @Test
    void coldStart_noActiveSituations_doesNothing() {
        loop.start("t1", baseGraph);

        dispatch.onColdStart(null);

        assertThat(recompileCalls).isEmpty();
    }

    @Test
    void coldStart_noTenants_doesNothing() {
        dispatch.onColdStart(null);
        assertThat(recompileCalls).isEmpty();
    }

    @Test
    void coldStart_multipleSituations_processedSequentially() {
        loop.start("t1", baseGraph);
        activeSituations.add(new ActiveSituation("sit-1", "key-1", "t1", 0.9,
                Map.of(), Instant.now().minusSeconds(120), Instant.now(), 5));
        activeSituations.add(new ActiveSituation("sit-2", "key-2", "t1", 0.8,
                Map.of(), Instant.now().minusSeconds(60), Instant.now(), 2));

        dispatch.onColdStart(null);

        assertThat(recompileCalls).containsExactly("t1:sit-1", "t1:sit-2");
    }

    @Test
    void coldStart_emptyResultDoesNotRefreshState() {
        loop.start("t1", baseGraph);
        recompilerReturnsResult = false;
        activeSituations.add(new ActiveSituation("sit-1", "key-1", "t1", 0.9,
                Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3));

        dispatch.onColdStart(null);

        assertThat(recompileCalls).containsExactly("t1:sit-1");
        assertThat(loop.getDesired("t1").nodes()).doesNotContainKey(NodeId.of("adapted"));
    }

    // --- toActiveSituation edge cases (supplement existing tests) ---

    @Test
    void toActiveSituation_preservesMetadata() {
        var ctx = SituationContext.initial("sit-1", "key-1", "t1", Instant.parse("2026-09-14T10:00:00Z"));
        var event = new SituationChangeEvent("t1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx, Map.of("env", "prod", "zone", "us-east"));

        var result = SituationRecompilerDispatch.toActiveSituation(event);

        assertThat(result.evidence()).containsEntry("env", "prod").containsEntry("zone", "us-east");
    }

    @Test
    void toActiveSituation_carriesTriggerCount() {
        var ctx = SituationContext.initial("sit-1", "key-1", "t1", Instant.now())
                .withDetection(new DetectionResult("g1", 0.7, DetectionSignal.DETECTED, Map.of()), Instant.now());
        var enriched = ctx.withDetection(
                new DetectionResult("g1", 0.8, DetectionSignal.DETECTED, Map.of()), Instant.now());
        var event = new SituationChangeEvent("t1", "sit-1", "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, enriched);

        var result = SituationRecompilerDispatch.toActiveSituation(event);

        assertThat(result.triggerCount()).isEqualTo(enriched.triggerCount());
    }

    // --- helpers ---

    private SituationChangeEvent triggeredEvent(String tenancyId, String situationId) {
        var ctx = SituationContext.initial(situationId, "key-1", tenancyId, Instant.now())
                .withDetection(new DetectionResult("g1", 0.9, DetectionSignal.DETECTED, Map.of()), Instant.now());
        return new SituationChangeEvent(tenancyId, situationId, "key-1",
                SituationChangeEvent.ChangeType.TRIGGERED, ctx, Map.of());
    }

    private SituationChangeEvent resolvedEvent(String tenancyId, String situationId) {
        var ctx = SituationContext.initial(situationId, "key-1", tenancyId, Instant.now());
        return new SituationChangeEvent(tenancyId, situationId, "key-1",
                SituationChangeEvent.ChangeType.RESOLVED, ctx, Map.of());
    }
}
