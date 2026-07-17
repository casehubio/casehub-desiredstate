package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var fastLatch = new CountDownLatch(3);

        var fastProv = new MockNodeProvisioner();
        fastProv.setHandledTypes(Set.of(FAST_TYPE));
        fastProv.setResyncInterval(Duration.ofSeconds(1));

        var slowProv = new MockNodeProvisioner();
        slowProv.setHandledTypes(Set.of(SLOW_TYPE));
        slowProv.setResyncInterval(Duration.ofSeconds(5));

        var router = new DefaultNodeProvisionerRouter(List.of(fastProv, slowProv));

        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(FAST_TYPE, SLOW_TYPE); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                for (DesiredNode node : desired.nodes().values()) {
                    if (node.type().equals(FAST_TYPE)) {
                        fastCount.incrementAndGet();
                        fastLatch.countDown();
                    }
                    if (node.type().equals(SLOW_TYPE)) slowCount.incrementAndGet();
                }
                return new ActualState(Map.of());
            }
        };

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), HumanGating.NONE),
                new DesiredNode(NodeId.of("s1"), SLOW_TYPE, new TestSpec("s"), HumanGating.NONE)
            ),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapterRouter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource()::stream,
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
        var nodesSeenByAdapter = new java.util.concurrent.CopyOnWriteArrayList<Set<NodeType>>();
        var latch = new CountDownLatch(2);

        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(FAST_TYPE, SLOW_TYPE); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                Set<NodeType> types = new java.util.HashSet<>();
                for (DesiredNode node : desired.nodes().values()) {
                    types.add(node.type());
                }
                nodesSeenByAdapter.add(types);
                latch.countDown();
                return new ActualState(Map.of());
            }
        };

        var prov = new MockNodeProvisioner();
        prov.setHandledTypes(Set.of(FAST_TYPE));
        prov.setResyncInterval(Duration.ofSeconds(1));

        var slowProv = new MockNodeProvisioner();
        slowProv.setHandledTypes(Set.of(SLOW_TYPE));
        slowProv.setResyncInterval(Duration.ofHours(1));

        var router = new DefaultNodeProvisionerRouter(List.of(prov, slowProv));
        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));

        var factory = new DefaultDesiredStateGraphFactory();
        var graph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), HumanGating.NONE),
                new DesiredNode(NodeId.of("s1"), SLOW_TYPE, new TestSpec("s"), HumanGating.NONE)
            ),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapterRouter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource()::stream,
            router,
            Duration.ofMillis(50)
        );

        loop.start("tenant-1", graph);

        assertTrue(latch.await(5, TimeUnit.SECONDS));

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

        ActualStateAdapter adapter = new ActualStateAdapter() {
            @Override public Set<NodeType> handledTypes() { return Set.of(FAST_TYPE, newType); }
            @Override public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
                for (DesiredNode node : desired.nodes().values()) {
                    if (node.type().equals(newType)) {
                        newTypeCount.incrementAndGet();
                        newTypeLatch.countDown();
                    }
                }
                return new ActualState(Map.of());
            }
        };

        var adapterRouter = new DefaultActualStateAdapterRouter(List.of(adapter));
        var factory = new DefaultDesiredStateGraphFactory();
        var initialGraph = factory.of(
            List.of(new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), HumanGating.NONE)),
            List.of()
        );

        loop = new ReconciliationLoop(
            new TransitionPlanner(),
            new ReconciliationLoopTest.TestTransitionExecutor(),
            adapterRouter,
            new FaultPolicyEngine(List.of()),
            new ReconciliationLoopTest.TestEventSource()::stream,
            router,
            Duration.ofMillis(50)
        );

        loop.start("tenant-1", initialGraph);

        var updatedGraph = factory.of(
            List.of(
                new DesiredNode(NodeId.of("f1"), FAST_TYPE, new TestSpec("f"), HumanGating.NONE),
                new DesiredNode(NodeId.of("n1"), newType, new TestSpec("n"), HumanGating.NONE)
            ),
            List.of()
        );
        loop.updateDesired("tenant-1", updatedGraph);

        assertTrue(newTypeLatch.await(5, TimeUnit.SECONDS),
            "New type should be reconciled after updateDesired recomputes interval groups");
    }

    record TestSpec(String value) implements NodeSpec {}
}
