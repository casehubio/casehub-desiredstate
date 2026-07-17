package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationLoopRequestReconciliationTest {

    private ReconciliationLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    @Test
    void requestReconciliationTriggersReconcileForTenant() throws Exception {
        var readActualCount = new AtomicInteger(0);
        var initialLatch = new CountDownLatch(1);
        var requestLatch = new CountDownLatch(1);
        var baselineSet = new AtomicInteger(-1);

        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("t")); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                int count = readActualCount.incrementAndGet();
                if (count == 1) {
                    initialLatch.countDown();
                } else if (baselineSet.get() >= 0 && count > baselineSet.get()) {
                    requestLatch.countDown();
                }
                return new ActualState(Map.of());
            }
        };

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapterRouter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource()::stream,
            java.time.Duration.ofMillis(50),
            java.time.Duration.ofHours(1)
        );

        var factory = new DefaultDesiredStateGraphFactory();
        var graph = ImmutableDesiredStateGraph.empty()
            .withNode(new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new ReconciliationLoopTest.TestSpec("x"), HumanGating.NONE));
        loop.start("tenant-1", graph);

        assertTrue(initialLatch.await(2, TimeUnit.SECONDS), "Initial reconciliation did not occur");
        int baseline = readActualCount.get();
        baselineSet.set(baseline);

        loop.requestReconciliation("tenant-1");

        assertTrue(requestLatch.await(2, TimeUnit.SECONDS),
            "Reconciliation not triggered by request. Baseline: " + baseline + ", current: " + readActualCount.get());

        assertTrue(readActualCount.get() > baseline,
            "Expected reconciliation to be triggered. Baseline: " + baseline + ", current: " + readActualCount.get());
    }

    @Test
    void requestReconciliationForUnknownTenantIsNoOp() {
        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(NodeType.of("t")); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                return new ActualState(Map.of());
            }
        };

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapterRouter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource()::stream
        );
        assertDoesNotThrow(() -> loop.requestReconciliation("nonexistent"));
    }
}
