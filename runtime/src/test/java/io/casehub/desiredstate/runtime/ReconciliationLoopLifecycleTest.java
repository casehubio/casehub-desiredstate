package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.ReconciliationListener;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationLoopLifecycleTest {

    private TransitionPlanner planner;
    private TestActualStateAdapter adapter;
    private FaultPolicyEngine faultPolicyEngine;
    private ReconciliationLoop loop;
    private List<ListenerCall> listenerCalls;

    record ListenerCall(String tenancyId, DesiredStateGraph desired, ActualState actual) {}

    private ActualStateAdapterRouter adapterRouter;

    @BeforeEach
    void setUp() {
        planner = new TransitionPlanner();
        adapter = new TestActualStateAdapter();
        adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        faultPolicyEngine = new FaultPolicyEngine(List.of());
        listenerCalls = new CopyOnWriteArrayList<>();
    }

    @Test
    void listenerFiresOnReconciliationCycle() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.ABSENT);

        CountDownLatch latch = new CountDownLatch(1);
        ReconciliationListener listener = (tid, d, a) -> {
            listenerCalls.add(new ListenerCall(tid, d, a));
            latch.countDown();
        };

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph, listener);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerCalls).isNotEmpty();
        assertThat(listenerCalls.get(0).tenancyId()).isEqualTo("t1");
        loop.stop("t1");
    }

    @Test
    void listenerFiresOnEmptyPlanCycles() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        CountDownLatch latch = new CountDownLatch(1);
        ReconciliationListener listener = (tid, d, a) -> {
            listenerCalls.add(new ListenerCall(tid, d, a));
            latch.countDown();
        };

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph, listener);

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerCalls).isNotEmpty();
        loop.stop("t1");
    }

    @Test
    void compareAndSetDesired_succeedsWhenExpectedMatches() {
        DesiredStateGraph graph1 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));
        DesiredStateGraph graph2 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("b"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph1);

        boolean swapped = loop.compareAndSetDesired("t1", graph1, graph2);
        assertThat(swapped).isTrue();
        assertThat(loop.getDesired("t1")).isSameAs(graph2);
        loop.stop("t1");
    }

    @Test
    void compareAndSetDesired_failsWhenExpectedDoesNotMatch() {
        DesiredStateGraph graph1 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));
        DesiredStateGraph graph2 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("b"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));
        DesiredStateGraph graph3 = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("c"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofSeconds(60));
        loop.start("t1", graph1);
        loop.updateDesired("t1", graph2);

        boolean swapped = loop.compareAndSetDesired("t1", graph1, graph3);
        assertThat(swapped).isFalse();
        assertThat(loop.getDesired("t1")).isSameAs(graph2);
        loop.stop("t1");
    }

    @Test
    void setListener_onRunningTenant() throws Exception {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));
        adapter.setStatus(NodeId.of("a"), NodeStatus.PRESENT);

        loop = new ReconciliationLoop(
            planner, new SucceedingExecutor(), adapterRouter,
            faultPolicyEngine, () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        loop.start("t1", graph);

        CountDownLatch latch = new CountDownLatch(1);
        loop.setListener("t1", (tid, d, a) -> {
            listenerCalls.add(new ListenerCall(tid, d, a));
            latch.countDown();
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listenerCalls).isNotEmpty();
        loop.stop("t1");
    }

    private record TestSpec() implements NodeSpec {}

    private static class TestActualStateAdapter implements ActualStateAdapter {
        private final Map<NodeId, NodeStatus> statuses = new HashMap<>();
        void setStatus(NodeId id, NodeStatus status) { statuses.put(id, status); }
        @Override
        public Set<NodeType> handledTypes() { return Set.of(NodeType.of("t")); }
        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(Map.copyOf(statuses));
        }
    }

    private static class SucceedingExecutor implements TransitionExecutor {
        @Override
        public io.smallrye.mutiny.Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
            for (OrderedStep step : plan.removals()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            for (OrderedStep step : plan.additions()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            return io.smallrye.mutiny.Uni.createFrom().item(new TransitionResult(outcomes));
        }
    }
}
