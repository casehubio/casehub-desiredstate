package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.cloudevents.CloudEvent;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.casehub.desiredstate.testing.TestTimeouts.AWAIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReconciliationLoopBuilderTest {

    private static final Duration TEST_DEBOUNCE = Duration.ofMillis(50);
    private static final Duration TEST_RESYNC = Duration.ofHours(1);

    private TransitionPlanner planner;
    private MockTransitionExecutor executor;
    private MockActualStateAdapter adapter;
    private DefaultActualStateAdapterRouter adapterRouter;
    private FaultPolicyEngine faultEngine;
    private ReconciliationLoop loop;

    private record TestSpec() implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        executor = new MockTransitionExecutor();
        adapter = new MockActualStateAdapter();
        adapter.setHandledTypes(Set.of(NodeType.of("t")));
        adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        faultEngine = new FaultPolicyEngine(List.of());
    }

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    @Test
    void builder_withDefaults_createsWorkingLoop() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE));
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        loop = ReconciliationLoop.builder(planner, executor, adapterRouter, faultEngine,
                () -> Multi.createFrom().nothing())
            .build();
        loop.start("t1", graph);

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(executor.executedPlans).isNotEmpty());
    }

    @Test
    void builder_withTimingOptions_appliesSettings() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE));
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        loop = ReconciliationLoop.builder(planner, executor, adapterRouter, faultEngine,
                () -> Multi.createFrom().nothing())
            .debounceWindow(TEST_DEBOUNCE)
            .resyncInterval(TEST_RESYNC)
            .build();
        loop.start("t1", graph);

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(executor.executedPlans).isNotEmpty());
    }

    @Test
    void builder_withCloudEventSink_receivesEvents() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE));
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        List<CloudEvent> events = new CopyOnWriteArrayList<>();

        loop = ReconciliationLoop.builder(planner, executor, adapterRouter, faultEngine,
                () -> Multi.createFrom().nothing())
            .debounceWindow(TEST_DEBOUNCE)
            .resyncInterval(TEST_RESYNC)
            .cloudEventSink(events::add)
            .build();
        loop.start("t1", graph);

        await().atMost(AWAIT).untilAsserted(() ->
            assertThat(events).isNotEmpty());
    }

    @Test
    void builder_withGlobalListeners_firesOnCycle() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE));
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        CountDownLatch latch = new CountDownLatch(1);
        GlobalReconciliationListener gl = (tid, d, a) -> latch.countDown();

        loop = ReconciliationLoop.builder(planner, executor, adapterRouter, faultEngine,
                () -> Multi.createFrom().nothing())
            .debounceWindow(TEST_DEBOUNCE)
            .resyncInterval(TEST_RESYNC)
            .globalListeners(List.of(gl))
            .build();
        loop.start("t1", graph);

        assertThat(latch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void builder_withCbrTracker_sharesInstance() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
                new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE));
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        CbrProposalTracker tracker = new CbrProposalTracker();
        List<CloudEvent>   events  = new CopyOnWriteArrayList<>();

        loop = ReconciliationLoop.builder(planner, executor, adapterRouter, faultEngine,
                                          () -> Multi.createFrom().nothing())
                                 .debounceWindow(TEST_DEBOUNCE)
                                 .resyncInterval(TEST_RESYNC)
                                 .cbrTracker(tracker)
                                 .cloudEventSink(events::add)
                                 .build();
        loop.start("t1", graph);

        await().atMost(AWAIT).untilAsserted(() ->
                                                    assertThat(events).isNotEmpty());
    }
}
