package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StateEvent;
import io.casehub.desiredstate.testing.CannedEventSource;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ReconciliationLoopCloudEventTest {

    private DefaultDesiredStateGraphFactory factory;
    private MockActualStateAdapter actualAdapter;
    private MockTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private FaultPolicyEngine faultEngine;
    private CannedEventSource testEventSource;
    private List<CloudEvent> capturedEvents;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new MockActualStateAdapter();
        actualAdapter.setHandledTypes(Set.of(NodeType.of("test")));
        testExecutor = new MockTransitionExecutor();
        planner = new TransitionPlanner();
        faultEngine = new FaultPolicyEngine(List.of());
        testEventSource = new CannedEventSource();
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

        await().atMost(AWAIT).until(() ->
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

        await().atMost(AWAIT).until(() ->
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
        await().atMost(AWAIT).until(() ->
            capturedEvents.stream().anyMatch(e ->
                e.getType().equals(DesiredStateEventTypes.NODE_FAULTED)));

        // Clear events and fix the provisioner
        capturedEvents.clear();
        testExecutor.failNodes.clear();

        // Second cycle: node is now PRESENT
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));
        testEventSource.emit(new StateEvent(NodeId.of("n1"), NodeStatus.PRESENT, "recovered"));

        // Wait for the recovery event
        await().atMost(AWAIT).until(() ->
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

        await().atMost(AWAIT).until(() ->
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
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());

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
        await().atMost(AWAIT).until(() -> !testExecutor.executedPlans.isEmpty());
        capturedEvents.clear();

        // Second cycle: node is present, but mark for deprovision failure
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));
        testExecutor.failDeprovisionNodes.add(NodeId.of("n1"));

        // Update to empty graph to trigger deprovision
        DesiredStateGraph emptyGraph = factory.empty();
        loop.updateDesired("test-tenant", emptyGraph);
        loop.requestReconciliation("test-tenant");

        await().atMost(AWAIT).until(() ->
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

        await().atMost(AWAIT).until(() ->
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
}
