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

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationLoopCbrOutcomeTest {

    private record TestSpec(String value) implements NodeSpec {}

    private DefaultDesiredStateGraphFactory factory;
    private ReconciliationLoopCloudEventTest.TestActualStateAdapter actualAdapter;
    private ReconciliationLoopCloudEventTest.TestTransitionExecutor testExecutor;
    private TransitionPlanner planner;
    private ReconciliationLoopCloudEventTest.TestEventSource testEventSource;
    private List<CloudEvent> capturedEvents;
    private CbrProposalTracker cbrTracker;
    private ReconciliationLoop loop;

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(10);
    private static final Duration TEST_RESYNC = Duration.ofSeconds(30);

    @BeforeEach
    void setUp() {
        factory = new DefaultDesiredStateGraphFactory();
        actualAdapter = new ReconciliationLoopCloudEventTest.TestActualStateAdapter();
        testExecutor = new ReconciliationLoopCloudEventTest.TestTransitionExecutor();
        planner = new TransitionPlanner();
        testEventSource = new ReconciliationLoopCloudEventTest.TestEventSource();
        capturedEvents = new CopyOnWriteArrayList<>();
        cbrTracker = new CbrProposalTracker();

        DefaultActualStateAdapterRouter router = new DefaultActualStateAdapterRouter(List.of(actualAdapter));
        DefaultMergedEventSource mergedSource = new DefaultMergedEventSource(List.of(testEventSource));
        FaultPolicyEngine faultEngine = new FaultPolicyEngine(List.of());

        loop = new ReconciliationLoop(planner, testExecutor, router, faultEngine,
            mergedSource, TEST_DEBOUNCE, TEST_RESYNC, capturedEvents::add, cbrTracker);
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

        loop.start("t1", graph);
        Thread.sleep(200);

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
        Thread.sleep(200);

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
        Thread.sleep(200);

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
        Thread.sleep(200);

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
        Thread.sleep(200);

        long count = capturedEvents.stream()
            .filter(e -> CbrEventTypes.CBR_OUTCOME.equals(e.getType()))
            .count();
        assertThat(count).isEqualTo(1);

        loop.requestReconciliation("t1");
        Thread.sleep(200);

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
