package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.testing.CannedEventSource;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationLoopTest {

    private DesiredStateGraphFactory factory;
    private MockActualStateAdapter actualAdapter;
    private MockTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private CannedEventSource testEventSource;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    @BeforeEach
    void setUp() {
        factory       = new DefaultDesiredStateGraphFactory();
        actualAdapter = new MockActualStateAdapter();
        actualAdapter.setHandledTypes(Set.of(NodeType.of("test")));
        testExecutor    = new MockTransitionExecutor();
        planner         = new TransitionPlanner();
        faultEngine     = new FaultPolicyEngine(List.of());
        testEventSource = new CannedEventSource();

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
                                 .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();
    }

    @AfterEach
    void tearDown() {
        // Stop all loops to clean up threads
        loop.stop("test-tenant");
    }

    @Test
    void start_triggersInitialReconciliation() {
        // Desired: two nodes. Actual: empty (all UNKNOWN).
        DesiredNode nodeA = node("a");
        DesiredNode nodeB = node("b");
        DesiredStateGraph desired = factory.of(List.of(nodeA, nodeB), List.of());

        // Actual returns empty — all nodes are UNKNOWN → should plan additions
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        // Wait for the initial reconciliation to produce an executed plan
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.isEmpty(), "Plan should have additions for unknown nodes");
        assertEquals(2, plan.additions().size(), "Both nodes should be planned for addition");
        assertTrue(plan.removals().isEmpty(), "No removals expected");
    }

    @Test
    void eventDriven_triggersReconciliation() {
        // Start with an already-reconciled state (actual matches desired — no diff)
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

        loop.start("test-tenant", desired);

        // Initial reconciliation should produce an empty plan (no diff), which is not executed
        // because TransitionPlanner produces it but ReconciliationLoop skips empty plans.
        // Wait a beat for the initial cycle to complete.
        await().atMost(AWAIT).until(() -> loop.getDesired("test-tenant") != null);

        // Clear any executed plans from the initial cycle
        testExecutor.executedPlans.clear();

        // Now simulate a state change: node "a" drifts, adapter reports it as ABSENT
        actualAdapter.setStatuses(Map.of());

        // Push an event to trigger reconciliation
        testEventSource.emit(new StateEvent(NodeId.of("a"), NodeStatus.ABSENT, "node lost"));

        // Wait for the debounced event-driven reconciliation
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.isEmpty());
        assertEquals(1, plan.additions().size());
        assertEquals(NodeId.of("a"), plan.additions().get(0).node().id());
    }

    @Test
    void updateDesired_nextCycleUsesNewGraph() {
        // Start with one node
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        // Wait for initial reconciliation
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        // Clear plans from initial reconciliation
        testExecutor.executedPlans.clear();

        // Update desired to have two nodes
        DesiredNode nodeB = node("b");
        DesiredStateGraph newDesired = factory.of(List.of(nodeA, nodeB), List.of());
        loop.updateDesired("test-tenant", newDesired);

        // Simulate node "a" now exists, but "b" is new
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.PRESENT));

        // Trigger event to force a reconciliation cycle
        testEventSource.emit(new StateEvent(NodeId.of("b"), NodeStatus.ABSENT, "new node"));

        // Wait for the event-driven reconciliation
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan plan = testExecutor.executedPlans.get(0);
        assertFalse(plan.isEmpty());
        // Only node "b" should be added (node "a" is already PRESENT)
        assertEquals(1, plan.additions().size());
        assertEquals(NodeId.of("b"), plan.additions().get(0).node().id());
    }

    @Test
    void stop_preventsSubsequentReconciliation() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", desired);

        // Wait for initial reconciliation
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        // Stop the loop
        loop.stop("test-tenant");

        // Subscription cancelled by stop() — structural guarantee, no timing needed
        int planCountAfterStop = testExecutor.executedPlans.size();
        testEventSource.emit(new StateEvent(NodeId.of("a"), NodeStatus.ABSENT, "ignored"));
        assertThat(testExecutor.executedPlans).hasSize(planCountAfterStop);
    }

    @Test
    void faultFeedback_appliesMutationsToDesiredGraph() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of());

        // Configure executor to fail node "a"
        testExecutor.failNodes.add(NodeId.of("a"));

        // Configure fault policy to add a replacement node on failure
        DesiredNode replacement = new DesiredNode(
            NodeId.of("a-replacement"), NodeType.of("test"), new TestSpec("replacement"), HumanGating.NONE);
        FaultPolicy addReplacementPolicy = (tid, event, current, actual) -> {
            if (event.node().equals(NodeId.of("a"))) {
                return List.of(new GraphMutation.AddNode(replacement));
            }
            return List.of();
        };
        faultEngine = new FaultPolicyEngine(List.of(addReplacementPolicy));

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loop.start("test-tenant", desired);

        // Wait for the initial reconciliation (which will fail node "a")
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        // The fault feedback should have added "a-replacement" to the desired graph.
        // Trigger another reconciliation to see the mutation take effect.
        // Actual: "a" is still absent (failed to provision)
        actualAdapter.setStatuses(Map.of());

        testExecutor.executedPlans.clear();
        testEventSource.emit(new StateEvent(NodeId.of("a"), NodeStatus.ABSENT, "still absent"));

        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

        TransitionPlan secondPlan = testExecutor.executedPlans.get(0);
        // The second plan should include the replacement node
        Set<String> plannedNodeIds = new HashSet<>();
        for (OrderedStep step : secondPlan.additions()) {
            plannedNodeIds.add(step.node().id().value());
        }
        assertTrue(plannedNodeIds.contains("a-replacement"),
            "Fault feedback should have added replacement node to desired graph. Planned: " + plannedNodeIds);
    }

    @Test
    void driftedNodes_produceNodeDegradedFaultEvents() {
        // Desired: one node "a". Actual: "a" is DRIFTED.
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

        // Capturing fault policy that records all FaultEvents
        List<FaultEvent> capturedEvents = new CopyOnWriteArrayList<>();
        FaultPolicy capturingPolicy = (tid, event, current, actual) -> {
            capturedEvents.add(event);
            return List.of();
        };
        faultEngine = new FaultPolicyEngine(List.of(capturingPolicy));

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loop.start("test-tenant", desired);

        // Wait for the initial reconciliation to process the DRIFTED node
        await().atMost(AWAIT).until(() -> !capturedEvents.isEmpty());

        // Should have exactly one NODE_DEGRADED event for node "a"
        assertEquals(1, capturedEvents.size(), "Expected one fault event for drifted node");
        FaultEvent event = capturedEvents.get(0);
        assertEquals(NodeId.of("a"), event.node());
        assertEquals(FaultType.NODE_DEGRADED, event.type());
        assertEquals("Node drifted from desired spec", event.detail());
    }

    @Test
    void driftDetection_addsFaultPolicyNodesAlongsideReprovision() {
        // Desired: node "a". Actual: "a" is DRIFTED.
        // After fix: "a" is re-provisioned AND fault-policy-injected "a-fix" is provisioned.
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());

        // FaultPolicy: on NODE_DEGRADED for "a", add a new node "a-fix"
        DesiredNode fixNode = new DesiredNode(
            NodeId.of("a-fix"), NodeType.of("test"), new TestSpec("fix"), HumanGating.NONE);
        FaultPolicy addFixPolicy = (tid, event, current, actual) -> {
            if (event.type() == FaultType.NODE_DEGRADED && event.node().equals(NodeId.of("a"))) {
                return List.of(new GraphMutation.AddNode(fixNode));
            }
            return List.of();
        };
        faultEngine = new FaultPolicyEngine(List.of(addFixPolicy));

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        // "a" is DRIFTED — planner will re-provision "a" and provision "a-fix" (UNKNOWN → addition)
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.DRIFTED));

        loop.start("test-tenant", desired);

        // Both "a" (re-provisioned) and "a-fix" (fault-policy-injected) appear in additions
        await().atMost(AWAIT).until(() ->
            testExecutor.executedPlans.stream().anyMatch(plan -> {
                var addedIds = plan.additions().stream()
                    .map(step -> step.node().id())
                    .toList();
                return addedIds.contains(NodeId.of("a")) && addedIds.contains(NodeId.of("a-fix"));
            }));
    }

    @Test
    void rejectedOutcome_producesApprovalRejectedFaultType() {
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(NodeId.of("a"), NodeStatus.ABSENT));

        // Configure executor to return Rejected for node "a"
        testExecutor.rejectNodes.add(NodeId.of("a"));

        List<FaultEvent> capturedEvents = new CopyOnWriteArrayList<>();
        FaultPolicy capturingPolicy = (tid, event, current, actual) -> {
            capturedEvents.add(event);
            return List.of();
        };
        faultEngine = new FaultPolicyEngine(List.of(capturingPolicy));

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loop.start("test-tenant", desired);

        await().atMost(AWAIT).until(() -> !capturedEvents.isEmpty());

        assertEquals(1, capturedEvents.size());
        FaultEvent event = capturedEvents.get(0);
        assertEquals(NodeId.of("a"), event.node());
        assertEquals(FaultType.APPROVAL_REJECTED, event.type());
    }

    @Test
    void deprovisionFailure_producesDeprovisionFailedFaultType() {
        // Desired: only node "a". Actual: both "a" (PRESENT) and "b" (PRESENT).
        // Node "b" is orphaned — not in desired — so planner will schedule it for removal.
        DesiredNode nodeA = node("a");
        DesiredStateGraph desired = factory.of(List.of(nodeA), List.of());
        actualAdapter.setStatuses(Map.of(
            NodeId.of("a"), NodeStatus.PRESENT,
            NodeId.of("b"), NodeStatus.PRESENT));

        // Configure executor to fail deprovision of node "b"
        testExecutor.failDeprovisionNodes.add(NodeId.of("b"));

        // Capturing fault policy that records all FaultEvents
        List<FaultEvent> capturedEvents = new CopyOnWriteArrayList<>();
        FaultPolicy capturingPolicy = (tid, event, current, actual) -> {
            capturedEvents.add(event);
            return List.of();
        };
        faultEngine = new FaultPolicyEngine(List.of(capturingPolicy));

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = ReconciliationLoop.builder(planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC).build();

        loop.start("test-tenant", desired);

        // Wait for the fault event to be captured
        await().atMost(AWAIT).until(() -> !capturedEvents.isEmpty());

        // Should have exactly one fault event for the failed deprovision of "b"
        assertEquals(1, capturedEvents.size(), "Expected one fault event for failed deprovision");
        FaultEvent event = capturedEvents.get(0);
        assertEquals(NodeId.of("b"), event.node());
        assertEquals(FaultType.DEPROVISION_FAILED, event.type(),
            "Deprovision failure should produce DEPROVISION_FAILED, not PROVISION_FAILED");
        assertEquals("test deprovision failure", event.detail());
    }

    // --- Test helpers ---

    private DesiredNode node(String id) {
        return new DesiredNode(NodeId.of(id), NodeType.of("test"), new TestSpec(id), HumanGating.NONE);
    }

    record TestSpec(String value) implements NodeSpec {}


}
