package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.EventSource;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.MultiEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ReconciliationLoopCloudEventTest {

    private DefaultDesiredStateGraphFactory factory;
    private TestActualStateAdapter actualAdapter;
    private TestTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private TestEventSource testEventSource;
    private List<CloudEvent> capturedEvents;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new TestActualStateAdapter();
        testExecutor = new TestTransitionExecutor();
        planner = new TransitionPlanner();
        faultEngine = new FaultPolicyEngine(List.of());
        testEventSource = new TestEventSource();
        capturedEvents = new CopyOnWriteArrayList<>();

        Consumer<CloudEvent> eventSink = capturedEvents::add;
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        loop = new ReconciliationLoop(
            planner, testExecutor, adapterRouter, faultEngine, testEventSource::stream,
            TEST_DEBOUNCE, TEST_RESYNC, eventSink);
    }

    @AfterEach
    void tearDown() {
        loop.stop("test-tenant");
    }

    @Test
    void emitsNodeFaultedOnProvisionFailure() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.ABSENT));
        testExecutor.failNodes.add(NodeId.of("n1"));

        loop.start("test-tenant", graph);

        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)
                && "n1".equals(e.getSubject())));

        CloudEvent faultEvent = capturedEvents.stream()
            .filter(e -> e.getType().equals(DesiredStateEventTypes.NODE_FAULTED))
            .findFirst()
            .orElseThrow();

        assertThat(faultEvent.getSubject()).isEqualTo("n1");
        assertThat(faultEvent.getExtension("tenancyid")).isEqualTo("test-tenant");
        assertThat(faultEvent.getExtension("faulttype")).isEqualTo("PROVISION_FAILED");
    }


    @Test
    void emitsNodeDriftedOnDriftDetection() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.DRIFTED));

        loop.start("test-tenant", graph);

        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_DRIFTED)
                && "n1".equals(e.getSubject())));
    }

    @Test
    void emitsNodeRecoveredWhenPreviouslyFaultedNodeIsPresent() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        // First cycle: node fails to provision
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.ABSENT));
        testExecutor.failNodes.add(NodeId.of("n1"));

        loop.start("test-tenant", graph);

        // Wait for the fault event
        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)));

        // Clear events and fix the provisioner
        capturedEvents.clear();
        testExecutor.failNodes.clear();

        // Second cycle: node is now PRESENT
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));
        testEventSource.emit(new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "recovered"));

        // Wait for the recovery event
        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_RECOVERED)
                && "n1".equals(e.getSubject())));
    }

    @Test
    void emitsReconciliationCompletedAfterEachCycle() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.ABSENT));

        loop.start("test-tenant", graph);

        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.RECONCILIATION_COMPLETED)));

        CloudEvent completedEvent = capturedEvents.stream()
            .filter(e -> e.getType().equals(DesiredStateEventTypes.RECONCILIATION_COMPLETED))
            .findFirst()
            .orElseThrow();

        assertThat(completedEvent.getSubject()).isEqualTo("test-tenant");
        assertThat(completedEvent.getExtension("tenancyid")).isEqualTo("test-tenant");
    }

    @Test
    void getDesired_returnsCurrentGraph() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        actualAdapter.setStatuses(Map.of());

        loop.start("test-tenant", graph);

        // Wait for the initial reconciliation to complete
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());

        DesiredStateGraph retrieved = loop.getDesired("test-tenant");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.nodes()).containsKey(NodeId.of("n1"));
    }

    @Test
    void getDesired_throwsForUnknownTenant() {
        assertThatThrownBy(() -> loop.getDesired("unknown-tenant"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No reconciliation loop running for tenant: unknown-tenant");
    }

    @Test
    void emitsNodeFaultedOnDeprovisionFailure() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        // Initial cycle: provision node
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.ABSENT));
        loop.start("test-tenant", graph);

        // Wait for initial provision
        await().atMost(Duration.ofSeconds(2)).until(() -> !testExecutor.executedPlans.isEmpty());
        capturedEvents.clear();

        // Second cycle: node is present, but mark for deprovision failure
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));
        testExecutor.failDeprovisionNodes.add(NodeId.of("n1"));

        // Update to empty graph to trigger deprovision
        DesiredStateGraph emptyGraph = factory.empty();
        loop.updateDesired("test-tenant", emptyGraph);
        loop.requestReconciliation("test-tenant");

        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)
                && "n1".equals(e.getSubject())
                && "DEPROVISION_FAILED".equals(e.getExtension("faulttype"))));

        CloudEvent faultEvent = capturedEvents.stream()
            .filter(e -> e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)
                && "DEPROVISION_FAILED".equals(e.getExtension("faulttype")))
            .findFirst()
            .orElseThrow();

        assertThat(faultEvent.getSubject()).isEqualTo("n1");
        assertThat(faultEvent.getExtension("tenancyid")).isEqualTo("test-tenant");
    }

    @Test
    void emitsNodeFaultedOnApprovalRejected() {
        DesiredNode node = new DesiredNode(
            NodeId.of("n1"), NodeType.of("test"), new TestSpec("v1"), HumanGating.NONE);
        DesiredStateGraph graph = factory.of(List.of(node), List.of());

        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.ABSENT));
        testExecutor.rejectNodes.add(NodeId.of("n1"));

        loop.start("test-tenant", graph);

        await().atMost(Duration.ofSeconds(2)).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)
                && "n1".equals(e.getSubject())
                && "APPROVAL_REJECTED".equals(e.getExtension("faulttype"))));

        CloudEvent faultEvent = capturedEvents.stream()
            .filter(e -> e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)
                && "APPROVAL_REJECTED".equals(e.getExtension("faulttype")))
            .findFirst()
            .orElseThrow();

        assertThat(faultEvent.getSubject()).isEqualTo("n1");
        assertThat(faultEvent.getExtension("tenancyid")).isEqualTo("test-tenant");
    }

    record TestSpec(String value) implements NodeSpec {}

    static class TestActualStateAdapter implements ActualStateAdapter {
        private volatile Map<NodeId, NodeStatus> statuses = Map.of();

        void setStatuses(Map<NodeId, NodeStatus> statuses) {
            this.statuses = Map.copyOf(statuses);
        }

        @Override
        public Set<NodeType> handledTypes() {
            return Set.of(NodeType.of("test"));
        }

        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(statuses);
        }
    }

    static class TestTransitionExecutor implements TransitionExecutor {
        final CopyOnWriteArrayList<TransitionPlan> executedPlans = new CopyOnWriteArrayList<>();
        final Set<NodeId> failNodes = ConcurrentHashMap.newKeySet();
        final Set<NodeId> failDeprovisionNodes = ConcurrentHashMap.newKeySet();
        final Set<NodeId> rejectNodes = ConcurrentHashMap.newKeySet();

        @Override
        public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            return Uni.createFrom().item(() -> {
                executedPlans.add(plan);

                Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
                for (OrderedStep step : plan.removals()) {
                    if (failDeprovisionNodes.contains(step.node().id())) {
                        outcomes.put(step.node().id(), new StepOutcome.Failed("test deprovision failure"));
                    } else {
                        outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                    }
                }
                for (OrderedStep step : plan.additions()) {
                    if (rejectNodes.contains(step.node().id())) {
                        outcomes.put(step.node().id(), new StepOutcome.Rejected("test rejection"));
                    } else if (failNodes.contains(step.node().id())) {
                        outcomes.put(step.node().id(), new StepOutcome.Failed("test failure"));
                    } else {
                        outcomes.put(step.node().id(), new StepOutcome.Succeeded());
                    }
                }
                return new TransitionResult(outcomes);
            });
        }
    }

    static class TestEventSource implements EventSource {
        private final AtomicReference<MultiEmitter<? super StateEvent>> emitterRef = new AtomicReference<>();
        private final Multi<StateEvent> multi;

        TestEventSource() {
            this.multi = Multi.createFrom().emitter(emitter -> emitterRef.set(emitter));
        }

        void emit(StateEvent event) {
            MultiEmitter<? super StateEvent> emitter = emitterRef.get();
            if (emitter != null) {
                emitter.emit(event);
            }
        }

        @Override
        public Multi<StateEvent> stream() {
            return multi;
        }
    }
}
