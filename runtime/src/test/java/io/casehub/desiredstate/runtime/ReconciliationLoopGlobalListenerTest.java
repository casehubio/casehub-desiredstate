package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GlobalReconciliationListener;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ReconciliationListener;
import io.casehub.desiredstate.testing.MockActualStateAdapter;
import io.casehub.desiredstate.testing.MockTransitionExecutor;
import io.smallrye.mutiny.Multi;
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

class ReconciliationLoopGlobalListenerTest {

    private TransitionPlanner planner;
    private MockActualStateAdapter adapter;
    private DefaultActualStateAdapterRouter adapterRouter;
    private FaultPolicyEngine faultPolicyEngine;

    private record TestSpec() implements NodeSpec { @Override public NodeType nodeType() { return NodeType.of("test"); } }

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        adapter = new MockActualStateAdapter();
        adapter.setHandledTypes(Set.of(NodeType.of("t")));
        adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        faultPolicyEngine = new FaultPolicyEngine(List.of());
    }

    @Test
    void globalListeners_fireOnReconciliationCycle() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        List<String> fired = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        GlobalReconciliationListener gl1 = (tid, d, a) -> { fired.add("gl1"); latch.countDown(); };
        GlobalReconciliationListener gl2 = (tid, d, a) -> { fired.add("gl2"); latch.countDown(); };

        ReconciliationLoop loop = ReconciliationLoop.builder(planner, new MockTransitionExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing())
            .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofSeconds(60))
            .globalListeners(List.of(gl1, gl2)).build();
        loop.start("t1", graph);

        assertThat(latch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("gl1", "gl2");
        loop.stop("t1");
    }

    @Test
    void globalListeners_fireAlongsidePerTenantListener() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        List<String> fired = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        GlobalReconciliationListener global = (tid, d, a) -> { fired.add("global"); latch.countDown(); };
        ReconciliationListener perTenant = (tid, d, a) -> { fired.add("tenant"); latch.countDown(); };

        ReconciliationLoop loop = ReconciliationLoop.builder(planner, new MockTransitionExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing())
            .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofSeconds(60))
            .globalListeners(List.of(global)).build();
        loop.start("t1", graph, perTenant);

        assertThat(latch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("global", "tenant");
        loop.stop("t1");
    }

    @Test
    void globalListener_exceptionDoesNotBlockOthers() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        List<String> fired = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        GlobalReconciliationListener failing = (tid, d, a) -> { fired.add("failing"); latch.countDown(); throw new RuntimeException("boom"); };
        GlobalReconciliationListener surviving = (tid, d, a) -> { fired.add("surviving"); latch.countDown(); };

        ReconciliationLoop loop = ReconciliationLoop.builder(planner, new MockTransitionExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing())
            .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofSeconds(60))
            .globalListeners(List.of(failing, surviving)).build();
        loop.start("t1", graph);

        assertThat(latch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(fired).containsExactly("failing", "surviving");
        loop.stop("t1");
    }

    @Test
    void globalListeners_fireOnEmptyPlanCycles() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        CountDownLatch latch = new CountDownLatch(1);
        GlobalReconciliationListener global = (tid, d, a) -> latch.countDown();

        ReconciliationLoop loop = ReconciliationLoop.builder(planner, new MockTransitionExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing())
            .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofSeconds(60))
            .globalListeners(List.of(global)).build();
        loop.start("t1", graph);

        assertThat(latch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        loop.stop("t1");
    }

    @Test
    void onTenantStopped_firesOnStop() throws Exception {
        DesiredNode       node  = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        CountDownLatch cycleLatch = new CountDownLatch(1);
        List<String>   stopped    = new CopyOnWriteArrayList<>();

        GlobalReconciliationListener gl = new GlobalReconciliationListener() {
            @Override
            public void onReconciliationCycleCompleted(String tenancyId, DesiredStateGraph desired, ActualState actual) {
                cycleLatch.countDown();
            }

            @Override
            public void onTenantStopped(String tenancyId) {
                stopped.add(tenancyId);
            }
        };

        ReconciliationLoop loop = ReconciliationLoop.builder(planner, new MockTransitionExecutor(),
                                                             adapterRouter, faultPolicyEngine, () -> Multi.createFrom().nothing())
                                                    .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofSeconds(60))
                                                    .globalListeners(List.of(gl)).build();
        loop.start("t1", graph);

        assertThat(cycleLatch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        loop.stop("t1");

        assertThat(stopped).containsExactly("t1");
    }

    @Test
    void onTenantStopped_exceptionDoesNotBlockOtherListeners() throws Exception {
        DesiredNode       node  = new DesiredNode(NodeId.of("a"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        CountDownLatch cycleLatch = new CountDownLatch(1);
        List<String>   stopped    = new CopyOnWriteArrayList<>();

        GlobalReconciliationListener failing = new GlobalReconciliationListener() {
            @Override
            public void onReconciliationCycleCompleted(String tid, DesiredStateGraph d, ActualState a) {
                cycleLatch.countDown();
            }

            @Override
            public void onTenantStopped(String tenancyId) {
                throw new RuntimeException("boom");
            }
        };
        GlobalReconciliationListener surviving = new GlobalReconciliationListener() {
            @Override
            public void onReconciliationCycleCompleted(String tid, DesiredStateGraph d, ActualState a) {}

            @Override
            public void onTenantStopped(String tenancyId) {
                stopped.add(tenancyId);
            }
        };

        ReconciliationLoop loop = ReconciliationLoop.builder(planner, new MockTransitionExecutor(),
                                                             adapterRouter, faultPolicyEngine, () -> Multi.createFrom().nothing())
                                                    .debounceWindow(Duration.ofMillis(50)).resyncInterval(Duration.ofSeconds(60))
                                                    .globalListeners(List.of(failing, surviving)).build();
        loop.start("t1", graph);

        assertThat(cycleLatch.await(AWAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        loop.stop("t1");

        assertThat(stopped).containsExactly("t1");
    }
}
