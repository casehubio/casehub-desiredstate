package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleManagerTest {

    private ReconciliationLoop loop;
    private LifecycleManager manager;
    private TrackingActualStateAdapter adapter;
    private DesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        adapter = new TrackingActualStateAdapter();
        factory = new DefaultDesiredStateGraphFactory();
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = new ReconciliationLoop(
            new TransitionPlanner(), new ImmediateSuccessExecutor(), adapterRouter,
            new FaultPolicyEngine(List.of()),
            () -> Multi.createFrom().nothing(),
            Duration.ofMillis(50), Duration.ofMillis(200));
        manager = new LifecycleManager(loop);
    }

    @AfterEach
    void tearDown() {
        manager.stop("t1");
    }

    @Test
    void singleGraph_startsReconciliationDirectly() throws Exception {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));

        Thread.sleep(300);
        assertThat(loop.getDesired("t1")).isSameAs(graph);
    }

    @Test
    void lifecycle_transitionsOnCompletion() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        adapter.makePresent(NodeId.of("build"));
        adapter.makePresent(NodeId.of("defend"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        // Wait for transition — build phase completes (all present), defend phase starts
        Thread.sleep(500);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("defend"));
    }

    @Test
    void lifecycle_staysOnPhaseUntilComplete() throws Exception {
        DesiredNode buildNode = new DesiredNode(NodeId.of("build"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredNode defendNode = new DesiredNode(NodeId.of("defend"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);

        DesiredStateGraph buildGraph = ImmutableDesiredStateGraph.empty().withNode(buildNode);
        DesiredStateGraph defendGraph = ImmutableDesiredStateGraph.empty().withNode(defendNode);

        // build node is ABSENT — phase should not complete
        adapter.makeAbsent(NodeId.of("build"));

        CompilationResult lifecycle = CompilationResult.lifecycle(List.of(
            new Phase("build", buildGraph, CompletionCondition.allPresent()),
            new Phase("defend", defendGraph, CompletionCondition.never())
        ));

        manager.start("t1", lifecycle);

        Thread.sleep(300);
        DesiredStateGraph current = loop.getDesired("t1");
        assertThat(current.nodes()).containsKey(NodeId.of("build"));
        assertThat(current.nodes()).doesNotContainKey(NodeId.of("defend"));
    }

    @Test
    void stop_cleansUpLifecycleState() {
        DesiredNode node = new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE);
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(node);
        adapter.makePresent(NodeId.of("a"));

        manager.start("t1", CompilationResult.single(graph));
        manager.stop("t1");

        assertThat(loop.activeTenantCount()).isZero();
    }

    private record TestSpec() implements NodeSpec {}

    private static class TrackingActualStateAdapter implements ActualStateAdapter {
        private final Map<NodeId, NodeStatus> statuses = new HashMap<>();
        void makePresent(NodeId id) { statuses.put(id, NodeStatus.PRESENT); }
        void makeAbsent(NodeId id) { statuses.put(id, NodeStatus.ABSENT); }
        @Override
        public Set<NodeType> handledTypes() { return Set.of(NodeType.of("t")); }
        @Override
        public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
            return new ActualState(Map.copyOf(statuses));
        }
    }

    private static class ImmediateSuccessExecutor implements TransitionExecutor {
        @Override
        public Uni<TransitionResult> execute(TransitionPlan plan, String tenancyId) {
            Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
            for (OrderedStep step : plan.removals()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            for (OrderedStep step : plan.additions()) outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            return Uni.createFrom().item(new TransitionResult(outcomes));
        }
    }
}
