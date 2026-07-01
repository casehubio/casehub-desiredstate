package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.testing.MockNodeProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationLoopSchedulingTest {

    static final NodeType FAST_TYPE = NodeType.of("fast");
    static final NodeType SLOW_TYPE = NodeType.of("slow");

    private ReconciliationLoop loop;

    @AfterEach
    void tearDown() {
        if (loop != null) loop.shutdown();
    }

    @Test
    void fastTypeReconciliesMoreOftenThanSlowType() throws Exception {
        var fastCount = new AtomicInteger(0);
        var slowCount = new AtomicInteger(0);
        var fastLatch = new CountDownLatch(3); // wait for 3 fast-type cycles

        var fastProv = new MockNodeProvisioner();
        fastProv.setHandledTypes(Set.of(FAST_TYPE));
        fastProv.setResyncInterval(Duration.ofSeconds(1));

        var slowProv = new MockNodeProvisioner();
        slowProv.setHandledTypes(Set.of(SLOW_TYPE));
        slowProv.setResyncInterval(Duration.ofSeconds(5));

        var router = new DefaultNodeProvisionerRouter(List.of(fastProv, slowProv));

        ActualStateAdapter adapter = (desired, tenancyId) -> {
            for (DesiredNode node : desired.nodes().values()) {
                if (node.type().equals(FAST_TYPE)) {
                    fastCount.incrementAndGet();
                    fastLatch.countDown();
                }
                if (node.type().equals(SLOW_TYPE)) slowCount.incrementAndGet();
            }
            return new ActualState(Map.of());
        };

        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), false),
                new DesiredNode(NodeId.of("s1"), SLOW_TYPE, new TestSpec("s"), false)
            ),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource(),
            router,
            Duration.ofMillis(50)
        );

        loop.start("tenant-1", graph);

        assertTrue(fastLatch.await(6, TimeUnit.SECONDS),
            "Fast type should reconcile at least 3 times within 6 seconds");
        assertThat(fastCount.get()).isGreaterThan(slowCount.get());
    }

    @Test
    void reconcileTypesFiltersGraphToTargetTypes() throws Exception {
        // When a type-filtered reconciliation fires, only nodes of the target type
        // should be visible to the ActualStateAdapter.
        var nodesSeenByAdapter = new java.util.concurrent.CopyOnWriteArrayList<Set<NodeType>>();
        var latch = new CountDownLatch(2); // initial full + at least one typed cycle

        ActualStateAdapter adapter = (desired, tenancyId) -> {
            Set<NodeType> types = new java.util.HashSet<>();
            for (DesiredNode node : desired.nodes().values()) {
                types.add(node.type());
            }
            nodesSeenByAdapter.add(types);
            latch.countDown();
            return new ActualState(Map.of());
        };

        var prov = new MockNodeProvisioner();
        prov.setHandledTypes(Set.of(FAST_TYPE));
        prov.setResyncInterval(Duration.ofSeconds(1));

        var slowProv = new MockNodeProvisioner();
        slowProv.setHandledTypes(Set.of(SLOW_TYPE));
        slowProv.setResyncInterval(Duration.ofHours(1)); // effectively never fires

        var router = new DefaultNodeProvisionerRouter(List.of(prov, slowProv));

        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), false),
                new DesiredNode(NodeId.of("s1"), SLOW_TYPE, new TestSpec("s"), false)
            ),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource(),
            router,
            Duration.ofMillis(50)
        );

        loop.start("tenant-1", graph);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // After the initial full-graph reconcile, subsequent fast-type cycles
        // should only see FAST_TYPE nodes
        boolean hasFilteredCycle = nodesSeenByAdapter.stream()
            .anyMatch(types -> types.equals(Set.of(FAST_TYPE)));
        assertTrue(hasFilteredCycle,
            "Expected at least one type-filtered reconciliation with only FAST_TYPE. Seen: " + nodesSeenByAdapter);
    }

    @Test
    void updateDesiredRecomputesIntervalGroupsWhenTypesChange() throws Exception {
        var newTypeCount = new AtomicInteger(0);
        var newTypeLatch = new CountDownLatch(1);
        NodeType newType = NodeType.of("new-fast");

        var fastProv = new MockNodeProvisioner();
        fastProv.setHandledTypes(Set.of(FAST_TYPE));
        fastProv.setResyncInterval(Duration.ofSeconds(1));

        var newProv = new MockNodeProvisioner();
        newProv.setHandledTypes(Set.of(newType));
        newProv.setResyncInterval(Duration.ofSeconds(1));

        var router = new DefaultNodeProvisionerRouter(List.of(fastProv, newProv));

        ActualStateAdapter adapter = (desired, tenancyId) -> {
            for (DesiredNode node : desired.nodes().values()) {
                if (node.type().equals(newType)) {
                    newTypeCount.incrementAndGet();
                    newTypeLatch.countDown();
                }
            }
            return new ActualState(Map.of());
        };

        var factory = new DefaultDesiredStateGraphFactory();
        var initialGraph = factory.of(
            List.of(new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), false)),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource(),
            router,
            Duration.ofMillis(50)
        );

        loop.start("tenant-1", initialGraph);

        // Update desired to include the new type
        var updatedGraph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), false),
                new DesiredNode(NodeId.of("n1"), newType, new TestSpec("n"), false)
            ),
            List.of()
        );
        loop.updateDesired("tenant-1", updatedGraph);

        assertTrue(newTypeLatch.await(5, TimeUnit.SECONDS),
            "New type should be reconciled after updateDesired recomputes interval groups");
    }

    record TestSpec(String value) implements NodeSpec {}
}
