package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeProvisionerRouter;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParallelTransitionExecutorTest {

    private static final NodeType TEST_TYPE = NodeType.of("test");
    private static final NodeSpec TEST_SPEC = new NodeSpec() {
        @Override
        public NodeType nodeType() { return TEST_TYPE; }
    };

    private NodeStepExecutor stepExecutor;
    private NodeProvisionerRouter router;
    private DefaultDesiredStateGraphFactory factory;

    @BeforeEach
    void setUp() {
        stepExecutor = mock(NodeStepExecutor.class);
        router = mock(NodeProvisionerRouter.class);
        factory = new DefaultDesiredStateGraphFactory();
        when(router.maxConcurrencyFor(any())).thenReturn(OptionalInt.empty());
    }

    @Test
    void independentNodes_executeInParallel() throws InterruptedException {
        var nodeA = new DesiredNode(NodeId.of("A"), TEST_SPEC, HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("B"), TEST_SPEC, HumanGating.NONE);
        var graph = factory.of(List.of(nodeA, nodeB), List.of());

        var additions = List.of(List.of(
                new OrderedStep(nodeA, StepAction.PROVISION),
                new OrderedStep(nodeB, StepAction.PROVISION)));
        var plan = new TransitionPlan(List.of(), List.of(), List.of(), additions, graph, graph);

        CountDownLatch bothRunning = new CountDownLatch(2);
        CopyOnWriteArrayList<String> executionLog = new CopyOnWriteArrayList<>();

        when(stepExecutor.execute(any(), any(), any(), any())).thenAnswer((Answer<StepOutcome>) invocation -> {
            DesiredNode node = invocation.getArgument(0);
            bothRunning.countDown();
            bothRunning.await(2, TimeUnit.SECONDS);
            executionLog.add(node.id().value());
            return new StepOutcome.Succeeded();
        });

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMinutes(1));
        TransitionResult result = executor.execute(plan, "tenant-1");

        assertThat(result.outcomes()).hasSize(2);
        assertInstanceOf(StepOutcome.Succeeded.class, result.outcomes().get(NodeId.of("A")));
        assertInstanceOf(StepOutcome.Succeeded.class, result.outcomes().get(NodeId.of("B")));
        assertThat(executionLog).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void failedNode_skipsDependentsInLaterLayers() {
        var nodeA = new DesiredNode(NodeId.of("A"), TEST_SPEC, HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("B"), TEST_SPEC, HumanGating.NONE);
        var graph = factory.of(List.of(nodeA, nodeB),
                Set.of(new Dependency(NodeId.of("B"), NodeId.of("A"))));

        var additions = List.of(
                List.of(new OrderedStep(nodeA, StepAction.PROVISION)),
                List.of(new OrderedStep(nodeB, StepAction.PROVISION)));
        var plan = new TransitionPlan(List.of(), List.of(), List.of(), additions, graph, graph);

        when(stepExecutor.execute(eq(nodeA), eq(StepAction.PROVISION), any(), any()))
                .thenReturn(new StepOutcome.Failed("provision error"));

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMinutes(1));
        TransitionResult result = executor.execute(plan, "tenant-1");

        assertInstanceOf(StepOutcome.Failed.class, result.outcomes().get(NodeId.of("A")));
        assertInstanceOf(StepOutcome.Failed.class, result.outcomes().get(NodeId.of("B")));
        assertThat(((StepOutcome.Failed) result.outcomes().get(NodeId.of("B"))).reason())
                .contains("dependency");
    }

    @Test
    void maxConcurrency_limitsConcurrentCalls() throws InterruptedException {
        var nodes = List.of(
                new DesiredNode(NodeId.of("A"), TEST_SPEC, HumanGating.NONE),
                new DesiredNode(NodeId.of("B"), TEST_SPEC, HumanGating.NONE),
                new DesiredNode(NodeId.of("C"), TEST_SPEC, HumanGating.NONE),
                new DesiredNode(NodeId.of("D"), TEST_SPEC, HumanGating.NONE));
        var graph = factory.of(nodes, List.of());

        var additions = List.of(nodes.stream()
                .map(n -> new OrderedStep(n, StepAction.PROVISION))
                .toList());
        var plan = new TransitionPlan(List.of(), List.of(), List.of(), additions, graph, graph);

        when(router.maxConcurrencyFor(TEST_TYPE)).thenReturn(OptionalInt.of(2));

        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        when(stepExecutor.execute(any(), any(), any(), any())).thenAnswer((Answer<StepOutcome>) invocation -> {
            int cur = concurrent.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, cur));
            Thread.sleep(50);
            concurrent.decrementAndGet();
            return new StepOutcome.Succeeded();
        });

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMinutes(1));
        TransitionResult result = executor.execute(plan, "tenant-1");

        assertThat(result.outcomes()).hasSize(4);
        result.outcomes().values().forEach(o -> assertInstanceOf(StepOutcome.Succeeded.class, o));
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void layerTimeout_marksUnfinishedAsFailed() {
        var nodeA = new DesiredNode(NodeId.of("A"), TEST_SPEC, HumanGating.NONE);
        var graph = factory.of(List.of(nodeA), List.of());

        var additions = List.of(List.of(new OrderedStep(nodeA, StepAction.PROVISION)));
        var plan = new TransitionPlan(List.of(), List.of(), List.of(), additions, graph, graph);

        when(stepExecutor.execute(any(), any(), any(), any())).thenAnswer((Answer<StepOutcome>) invocation -> {
            Thread.sleep(5000);
            return new StepOutcome.Succeeded();
        });

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMillis(100));
        TransitionResult result = executor.execute(plan, "tenant-1");

        assertInstanceOf(StepOutcome.Failed.class, result.outcomes().get(NodeId.of("A")));
        assertThat(((StepOutcome.Failed) result.outcomes().get(NodeId.of("A"))).reason())
                .contains("timeout");
    }

    @Test
    void removals_failurePropagates_toDependencies() {
        var nodeA = new DesiredNode(NodeId.of("A"), TEST_SPEC, HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("B"), TEST_SPEC, HumanGating.NONE);
        var graph = factory.of(List.of(nodeA, nodeB),
                Set.of(new Dependency(NodeId.of("B"), NodeId.of("A"))));

        // Removals: leaves first → B (leaf) in layer 0, A (root) in layer 1
        var removals = List.of(
                List.of(new OrderedStep(nodeB, StepAction.DEPROVISION)),
                List.of(new OrderedStep(nodeA, StepAction.DEPROVISION)));
        var plan = new TransitionPlan(removals, List.of(), List.of(), List.of(), graph, graph);

        when(stepExecutor.execute(eq(nodeB), eq(StepAction.DEPROVISION), any(), any()))
                .thenReturn(new StepOutcome.Failed("deprovision error"));

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMinutes(1));
        TransitionResult result = executor.execute(plan, "tenant-1");

        assertInstanceOf(StepOutcome.Failed.class, result.outcomes().get(NodeId.of("B")));
        assertInstanceOf(StepOutcome.Failed.class, result.outcomes().get(NodeId.of("A")));
        assertThat(((StepOutcome.Failed) result.outcomes().get(NodeId.of("A"))).reason())
                .contains("dependency");
    }

    @Test
    void emptyPlan_returnsEmptyResult() {
        var graph = factory.empty();
        var plan = new TransitionPlan(List.of(), List.of(), List.of(), List.of(), graph, graph);

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMinutes(1));
        TransitionResult result = executor.execute(plan, "tenant-1");

        assertThat(result.outcomes()).isEmpty();
    }

    @Test
    void mixedPhases_executeInOrder() {
        var nodeA = new DesiredNode(NodeId.of("A"), TEST_SPEC, HumanGating.NONE);
        var nodeB = new DesiredNode(NodeId.of("B"), TEST_SPEC, HumanGating.NONE);
        var graph = factory.of(List.of(nodeA, nodeB), List.of());

        var removals = List.of(List.of(new OrderedStep(nodeA, StepAction.DEPROVISION)));
        var additions = List.of(List.of(new OrderedStep(nodeB, StepAction.PROVISION)));
        var plan = new TransitionPlan(removals, List.of(), List.of(), additions, graph, graph);

        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        when(stepExecutor.execute(any(), any(), any(), any())).thenAnswer((Answer<StepOutcome>) invocation -> {
            DesiredNode node = invocation.getArgument(0);
            StepAction action = invocation.getArgument(1);
            order.add(action.name() + ":" + node.id().value());
            return new StepOutcome.Succeeded();
        });

        var executor = new ParallelTransitionExecutor(stepExecutor, router, Duration.ofMinutes(1));
        executor.execute(plan, "tenant-1");

        assertThat(order).containsExactly("DEPROVISION:A", "PROVISION:B");
    }
}
