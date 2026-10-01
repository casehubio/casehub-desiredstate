package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.Exemption;
import io.casehub.desiredstate.api.ExemptionSpec;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.InMemoryExemptionStore;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.RevertCondition;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.testing.CannedEventSource;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationLoopDriftPolicyTest {

    private DefaultDesiredStateGraphFactory factory;
    private MockActualStateAdapter actualAdapter;
    private MockTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private CannedEventSource testEventSource;
    private InMemoryExemptionStore exemptionStore;
    private List<CloudEvent> emittedEvents;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);
    private static final Duration AWAIT = Duration.ofSeconds(5);

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new MockActualStateAdapter();
        actualAdapter.setHandledTypes(Set.of(NodeType.of("test")));
        testExecutor = new MockTransitionExecutor();
        planner = new TransitionPlanner();
        faultEngine = new FaultPolicyEngine(List.of());
        testEventSource = new CannedEventSource();
        exemptionStore = new InMemoryExemptionStore();
        emittedEvents = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    private ReconciliationLoop buildLoop(DriftPolicy... policies) {
        var driftEngine = new DriftPolicyEngine(List.of(policies));
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        var evictionListener = new ExemptionEvictionListener(exemptionStore);
        return ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC)
            .cloudEventSink(emittedEvents::add)
            .driftPolicyEngine(driftEngine)
            .exemptionStore(exemptionStore)
            .globalListeners(List.of(evictionListener))
            .build();
    }

    private DesiredNode node(String id) {
        return new DesiredNode(NodeId.of(id), new TestSpec(), HumanGating.NONE);
    }

    @Test
    void noDriftPolicy_driftedNodeIsReconciled() {
        loop = buildLoop();
        DesiredNode a = node("a");
        var graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());
        var plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.flatAdditions().isEmpty());
    }

    @Test
    void exemptPolicy_driftedNodeNotReprovisioned() {
        DriftPolicy exempt = (id, s, n, c) ->
            DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), Map.of()));
        loop = buildLoop(exempt);
        DesiredNode a = node("a");
        var graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
            .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.RECONCILIATION_COMPLETED)));
        assertTrue(testExecutor.executedPlans.isEmpty() ||
            testExecutor.executedPlans.stream().allMatch(TransitionPlan::isEmpty));
    }

    @Test
    void exemptPolicy_emitsNodeDriftExemptedEvent() {
        DriftPolicy exempt = (id, s, n, c) ->
            DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), Map.of()));
        loop = buildLoop(exempt);
        DesiredNode a = node("a");
        var graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
            .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.NODE_DRIFT_EXEMPTED)));
        assertTrue(emittedEvents.stream()
            .noneMatch(e -> e.getType().equals(DesiredStateEventTypes.NODE_DRIFTED)));
    }

    @Test
    void exemptionStoreGrant_exemptsWithoutPolicy() {
        loop = buildLoop();
        DesiredNode a = node("a");
        var graph = factory.of(List.of(a), List.of());
        exemptionStore.grant("t1", NodeId.of("a"), new Exemption(
            NodeId.of("a"), new ExemptionSpec(new RevertCondition.Never(), Map.of()),
            Instant.now(), null));
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
            .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.NODE_DRIFT_EXEMPTED)));
        assertTrue(testExecutor.executedPlans.isEmpty() ||
            testExecutor.executedPlans.stream().allMatch(TransitionPlan::isEmpty));
    }


    @Test
    void durationExemption_reconciledAfterExpiry() {
        java.util.concurrent.atomic.AtomicBoolean shouldExempt = new java.util.concurrent.atomic.AtomicBoolean(true);
        DriftPolicy exempt = (id, s, n, c) -> shouldExempt.get()
            ? DriftDecision.exempt(new ExemptionSpec(new RevertCondition.OnDuration(Duration.ofMillis(100)), Map.of()))
            : DriftDecision.reconcile();
        loop = buildLoop(exempt);
        DesiredNode a = node("a");
        var graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
            .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.NODE_DRIFT_EXEMPTED)));
        emittedEvents.clear();
        testExecutor.executedPlans.clear();
        shouldExempt.set(false);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        loop.requestReconciliation("t1");
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());
        assertFalse(testExecutor.executedPlans.get(0).flatAdditions().isEmpty());
    }

    @Test
    void onStatusChange_revokesExemptionWhenStatusMatches() {
        exemptionStore.grant("t1", NodeId.of("a"), new Exemption(
            NodeId.of("a"), new ExemptionSpec(
                new RevertCondition.OnStatusChange(Set.of(NodeStatus.DRIFTED)), Map.of()),
            Instant.now(), null));
        loop = buildLoop();
        DesiredNode a = node("a");
        var graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());
        assertTrue(exemptionStore.get("t1", NodeId.of("a")).isEmpty());
    }

    @Test
    void retriggering_policyReGrantsEachCycle() {
        DriftPolicy exempt = (id, s, n, c) ->
                                     DriftDecision.exempt(new ExemptionSpec(
                                             new RevertCondition.OnDuration(Duration.ofMinutes(30)), Map.of()));
        loop = buildLoop(exempt);
        DesiredNode a     = node("a");
        var         graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
                                                       .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.NODE_DRIFT_EXEMPTED)));
        assertTrue(exemptionStore.get("t1", NodeId.of("a")).isPresent());
        emittedEvents.clear();
        loop.requestReconciliation("t1");
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
                                                       .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.NODE_DRIFT_EXEMPTED)));
        assertTrue(exemptionStore.get("t1", NodeId.of("a")).isPresent());
    }

    @Test
    void reconciliationCompletedData_includesExemptedCount() {
        DriftPolicy exempt = (id, s, n, c) ->
                                     DriftDecision.exempt(new ExemptionSpec(new RevertCondition.Never(), Map.of()));
        loop = buildLoop(exempt);
        DesiredNode a     = node("a");
        var         graph = factory.of(List.of(a), List.of());
        actualAdapter.setStatus(NodeId.of("a"), NodeStatus.DRIFTED);
        loop.start("t1", graph);
        await().atMost(AWAIT).until(() -> emittedEvents.stream()
                                                       .anyMatch(e -> e.getType().equals(DesiredStateEventTypes.RECONCILIATION_COMPLETED)));
        var completedEvent = emittedEvents.stream()
                                          .filter(e -> e.getType().equals(DesiredStateEventTypes.RECONCILIATION_COMPLETED))
                                          .findFirst().orElseThrow();
        String json = new String(completedEvent.getData().toBytes());
        assertTrue(json.contains("\"exemptedCount\":1"));
    }

    @Test
    void eviction_removedExemptNodesEvictedFromStore() {
        ExemptionEvictionListener evictionListener = new ExemptionEvictionListener(exemptionStore);
        exemptionStore.grant("t1", NodeId.of("a"), new Exemption(
            NodeId.of("a"), new ExemptionSpec(new RevertCondition.Never(), Map.of()),
            Instant.now(), null));
        assertTrue(exemptionStore.get("t1", NodeId.of("a")).isPresent());
        DesiredNode b = node("b");
        var graph = factory.of(List.of(b), List.of());
        evictionListener.onReconciliationCycleCompleted("t1", graph,
            new ActualState(Map.of(NodeId.of("b"), NodeStatus.PRESENT)));
        assertTrue(exemptionStore.get("t1", NodeId.of("a")).isEmpty());
    }

    @Test
    void eviction_tenantStopClearsAllExemptions() {
        ExemptionEvictionListener evictionListener = new ExemptionEvictionListener(exemptionStore);
        exemptionStore.grant("t1", NodeId.of("a"), new Exemption(
            NodeId.of("a"), new ExemptionSpec(new RevertCondition.Never(), Map.of()),
            Instant.now(), null));
        exemptionStore.grant("t1", NodeId.of("b"), new Exemption(
            NodeId.of("b"), new ExemptionSpec(new RevertCondition.Never(), Map.of()),
            Instant.now(), null));
        evictionListener.onTenantStopped("t1");
        assertTrue(exemptionStore.get("t1", NodeId.of("a")).isEmpty());
        assertTrue(exemptionStore.get("t1", NodeId.of("b")).isEmpty());
    }

    private static class TestSpec implements NodeSpec {
        @Override public NodeType nodeType() { return NodeType.of("test"); }
    }
}
