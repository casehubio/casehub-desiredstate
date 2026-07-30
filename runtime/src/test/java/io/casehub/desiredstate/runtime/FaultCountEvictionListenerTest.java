package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FaultCountEvictionListenerTest {

    private InMemoryFaultCountStore store;
    private FaultCountEvictionListener listener;

    private record TestSpec() implements NodeSpec {}

    @BeforeEach
    void setUp() {
        store = new InMemoryFaultCountStore();
        listener = new FaultCountEvictionListener(store);
    }

    @Test
    void onCycleCompleted_evictsCountsForRemovedNodes() {
        store.incrementAndGet("ns1", "t1", NodeId.of("a"));
        store.incrementAndGet("ns1", "t1", NodeId.of("b"));
        store.incrementAndGet("ns2", "t1", NodeId.of("b"));

        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));

        listener.onReconciliationCycleCompleted("t1", graph, new ActualState(Map.of()));

        assertThat(store.getCount("ns1", "t1", NodeId.of("a"))).isEqualTo(1);
        assertThat(store.getCount("ns1", "t1", NodeId.of("b"))).isZero();
        assertThat(store.getCount("ns2", "t1", NodeId.of("b"))).isZero();
    }

    @Test
    void onCycleCompleted_retainsCountsForExistingNodes() {
        store.incrementAndGet("ns1", "t1", NodeId.of("a"));
        store.incrementAndGet("ns2", "t1", NodeId.of("a"));

        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("a"), NodeType.of("t"), new TestSpec(), HumanGating.NONE));

        listener.onReconciliationCycleCompleted("t1", graph, new ActualState(Map.of()));

        assertThat(store.getCount("ns1", "t1", NodeId.of("a"))).isEqualTo(1);
        assertThat(store.getCount("ns2", "t1", NodeId.of("a"))).isEqualTo(1);
    }

    @Test
    void onTenantStopped_evictsAllCountsForTenant() {
        store.incrementAndGet("ns1", "t1", NodeId.of("a"));
        store.incrementAndGet("ns2", "t1", NodeId.of("b"));
        store.incrementAndGet("ns1", "t2", NodeId.of("c"));

        listener.onTenantStopped("t1");

        assertThat(store.getCount("ns1", "t1", NodeId.of("a"))).isZero();
        assertThat(store.getCount("ns2", "t1", NodeId.of("b"))).isZero();
        assertThat(store.getCount("ns1", "t2", NodeId.of("c"))).isEqualTo(1);
    }

    @Test
    void onCycleCompleted_emptyGraph_evictsAllForTenant() {
        store.incrementAndGet("ns1", "t1", NodeId.of("a"));
        store.incrementAndGet("ns2", "t1", NodeId.of("b"));

        listener.onReconciliationCycleCompleted("t1",
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));

        assertThat(store.getCount("ns1", "t1", NodeId.of("a"))).isZero();
        assertThat(store.getCount("ns2", "t1", NodeId.of("b"))).isZero();
    }
}
