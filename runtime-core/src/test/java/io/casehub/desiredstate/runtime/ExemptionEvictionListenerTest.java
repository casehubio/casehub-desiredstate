package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.ExemptionStore;
import io.casehub.desiredstate.api.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExemptionEvictionListenerTest {

    private static final NodeType TEST_TYPE = NodeType.of("test");
    private static final NodeSpec TEST_SPEC = new NodeSpec() {
        @Override
        public NodeType nodeType() { return TEST_TYPE; }
    };

    private ExemptionStore store;
    private ExemptionEvictionListener listener;

    @BeforeEach
    void setUp() {
        store = mock(ExemptionStore.class);
        listener = new ExemptionEvictionListener(store);
    }

    @Test
    void onReconciliationCycleCompleted_evictsWithGraphNodeIds() {
        var graph = mock(DesiredStateGraph.class);
        var actual = mock(ActualState.class);
        var n1 = new DesiredNode(NodeId.of("n1"), TEST_SPEC, HumanGating.NONE);
        var n2 = new DesiredNode(NodeId.of("n2"), TEST_SPEC, HumanGating.NONE);
        when(graph.nodes()).thenReturn(Map.of(NodeId.of("n1"), n1, NodeId.of("n2"), n2));

        listener.onReconciliationCycleCompleted("tenant-1", graph, actual);

        verify(store).evict("tenant-1", Set.of(NodeId.of("n1"), NodeId.of("n2")));
    }

    @Test
    void onTenantStopped_evictsWithEmptySet() {
        listener.onTenantStopped("tenant-1");

        verify(store).evict("tenant-1", Set.of());
    }
}
