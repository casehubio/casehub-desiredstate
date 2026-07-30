package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.CbrEventTypes;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.desiredstate.api.CbrProposal;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionResult;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationLoopCbrOutcomeTest {

    private record TestSpec(String value) implements NodeSpec {}

    private DefaultDesiredStateGraphFactory factory;
    private MockActualStateAdapter actualAdapter;
    private MockTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private CannedEventSource testEventSource;
    private List<CloudEvent> capturedEvents;
    private CbrProposalTracker cbrTracker;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(10);
    private static final Duration TEST_RESYNC = Duration.ofSeconds(30);

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new MockActualStateAdapter();
        actualAdapter.setHandledTypes(Set.of(NodeType.of("t")));
        testExecutor = new MockTransitionExecutor();
        planner = new TransitionPlanner();
        testEventSource = new CannedEventSource();
        capturedEvents = new CopyOnWriteArrayList<>();
        cbrTracker = new CbrProposalTracker();

        DefaultActualStateAdapterRouter router = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        DefaultMergedEventSource mergedSource = new DefaultMergedEventSource(List.of(testEventSource));
        FaultPolicyEngine faultEngine = new FaultPolicyEngine(List.of());

        loop = ReconciliationLoop.builder(planner, testExecutor, router, faultEngine, mergedSource)
            .debounceWindow(TEST_DEBOUNCE).resyncInterval(TEST_RESYNC)
            .cloudEventSink(capturedEvents::add).cbrTracker(cbrTracker).build();
    }

    @AfterEach
    void tearDown() {
        loop.shutdown();
    }

    @Test
    void noCbrProposals_noOutcomeEvents() throws Exception {
        DesiredStateGraph graph = factory.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));
        actualAdapter.setStatuses(Map.of(NodeId.of("n1"), NodeStatus.PRESENT));

        CountDownLatch cycleLatch = new CountDownLatch(1);
        loop.start("t1", graph, (tid, d, a) -> cycleLatch.countDown());
        assertTrue(cycleLatch.await(AWAIT.toSeconds(), TimeUnit.SECONDS));

        assertThat(capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .toList()).isEmpty();
    }

    @Test
    void pendingProposal_matchedOnEmptyPlan_alreadyPresent() throws Exception {
        var nodeId = NodeId.of("n1");
        DesiredStateGraph graph = factory.empty().withNode(
            new DesiredNode(nodeId, NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));
        actualAdapter.setStatuses(Map.of(nodeId, NodeStatus.PRESENT));

        cbrTracker.recordProposal("t1", new CbrProposal(
            "case-42", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        loop.start("t1", graph);
        await().atMost(AWAIT).until(() ->
            capturedEvents.stream().anyMatch(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType())));

        List<CloudEvent> cbrEvents = capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .toList();
        assertThat(cbrEvents).hasSize(1);
        assertThat(cbrEvents.get(0).getSubject()).isEqualTo("case-42");
        assertThat(cbrEvents.get(0).getExtension("cbrpath")).isEqualTo("fault");
    }

    @Test
    void pendingProposal_matchedAfterExecution_succeeded() throws Exception {
        var nodeId = NodeId.of("n1");
        DesiredStateGraph graph = factory.empty().withNode(
            new DesiredNode(nodeId, NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));
        actualAdapter.setStatuses(Map.of());

        cbrTracker.recordProposal("t1", new CbrProposal(
            "case-99", CbrPath.SITUATION, Set.of(nodeId), Instant.now()));

        loop.start("t1", graph);
        await().atMost(AWAIT).until(() ->
            capturedEvents.stream().anyMatch(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType())));

        List<CloudEvent> cbrEvents = capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .toList();
        assertThat(cbrEvents).hasSize(1);
        assertThat(cbrEvents.get(0).getSubject()).isEqualTo("case-99");
        assertThat(cbrEvents.get(0).getExtension("cbrpath")).isEqualTo("situation");
        assertThat(cbrEvents.get(0).getExtension("successrate")).isEqualTo("1.0");
    }

    @Test
    void pendingProposal_matchedAfterExecution_failed() throws Exception {
        var nodeId = NodeId.of("n1");
        DesiredStateGraph graph = factory.empty().withNode(
            new DesiredNode(nodeId, NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));
        actualAdapter.setStatuses(Map.of());
        testExecutor.failNodes.add(nodeId);

        cbrTracker.recordProposal("t1", new CbrProposal(
            "case-fail", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        loop.start("t1", graph);
        await().atMost(AWAIT).until(() ->
            capturedEvents.stream().anyMatch(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType())));

        List<CloudEvent> cbrEvents = capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .toList();
        assertThat(cbrEvents).hasSize(1);
        assertThat(cbrEvents.get(0).getExtension("successrate")).isEqualTo("0.0");
    }

    @Test
    void proposalConsumedAfterMatch_noDoubleEmission() throws Exception {
        var nodeId = NodeId.of("n1");
        DesiredStateGraph graph = factory.empty().withNode(
            new DesiredNode(nodeId, NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));
        actualAdapter.setStatuses(Map.of(nodeId, NodeStatus.PRESENT));

        cbrTracker.recordProposal("t1", new CbrProposal(
            "case-once", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        loop.start("t1", graph);
        await().atMost(AWAIT).until(() ->
            capturedEvents.stream().anyMatch(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType())));

        long count = capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .count();
        assertThat(count).isEqualTo(1);

        CountDownLatch secondCycleLatch = new CountDownLatch(1);
        loop.setListener("t1", (tid, d, a) -> secondCycleLatch.countDown());
        loop.requestReconciliation("t1");
        assertTrue(secondCycleLatch.await(AWAIT.toSeconds(), TimeUnit.SECONDS));

        long countAfter = capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .count();
        assertThat(countAfter).isEqualTo(1);
    }

    @Test
    void tenantStop_clearsPendingProposals() throws Exception {
        var nodeId = NodeId.of("n1");
        DesiredStateGraph graph = factory.empty().withNode(
            new DesiredNode(nodeId, NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));
        actualAdapter.setStatuses(Map.of(nodeId, NodeStatus.PRESENT));

        cbrTracker.recordProposal("t1", new CbrProposal(
            "case-abandoned", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        loop.start("t1", graph);
        loop.stop("t1");

        assertThat(cbrTracker.matchOutcomes("t1",
            new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded())),
            factory.empty())).isEmpty();
    }
}
