package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisionEscalationFaultPolicyTest {

    private PipelineWorld world;
    private FaultCountStore store;
    private ProvisionEscalationFaultPolicy policy;

    @BeforeEach
    void setUp() {
        world = new PipelineWorld();
        store = new InMemoryFaultCountStore();
        policy = new ProvisionEscalationFaultPolicy(world, store);
    }

    @Test
    void tenantIsolation_independentFaultCounts() {
        DesiredNode node = new DesiredNode(NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
            new IngestionSpec("data", 100, "json"), HumanGating.NONE);
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory().of(List.of(node), List.of());
        FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "down");
        ActualState actual = new ActualState(Map.of());

        for (int i = 0; i < 3; i++) {
            policy.onFault("tenant-a", fault, graph, actual);
        }
        policy.onFault("tenant-b", fault, graph, actual);

        assertThat(store.getCount("pipeline-escalation", "tenant-a", NodeId.of("ingest"))).isEqualTo(3);
        assertThat(store.getCount("pipeline-escalation", "tenant-b", NodeId.of("ingest"))).isEqualTo(1);
    }

    @Test
    void namespaceIsolation_fromOtherPolicies() {
        FaultCountStore shared = new InMemoryFaultCountStore();
        shared.incrementAndGet("other-policy", "t1", NodeId.of("ingest"));
        shared.incrementAndGet("other-policy", "t1", NodeId.of("ingest"));

        ProvisionEscalationFaultPolicy p = new ProvisionEscalationFaultPolicy(world, shared);
        DesiredNode node = new DesiredNode(NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
            new IngestionSpec("data", 100, "json"), HumanGating.NONE);
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory().of(List.of(node), List.of());
        FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "err");

        p.onFault("t1", fault, graph, new ActualState(Map.of()));

        assertThat(shared.getCount("pipeline-escalation", "t1", NodeId.of("ingest"))).isEqualTo(1);
        assertThat(shared.getCount("other-policy", "t1", NodeId.of("ingest"))).isEqualTo(2);
    }

    @Test
    void lazyEviction_removedNodeCleansStore() {
        DesiredNode node = new DesiredNode(NodeId.of("ingest"), PipelineNodeTypes.INGESTION,
            new IngestionSpec("data", 100, "json"), HumanGating.NONE);
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory().of(List.of(node), List.of());
        FaultEvent fault = new FaultEvent(NodeId.of("ingest"), FaultType.PROVISION_FAILED, "err");

        policy.onFault("t1", fault, graph, new ActualState(Map.of()));
        policy.onFault("t1", fault, graph, new ActualState(Map.of()));
        assertThat(store.getCount("pipeline-escalation", "t1", NodeId.of("ingest"))).isEqualTo(2);

        DesiredStateGraph emptyGraph = new DefaultDesiredStateGraphFactory().of(List.of(), List.of());
        policy.onFault("t1", fault, emptyGraph, new ActualState(Map.of()));
        assertThat(store.getCount("pipeline-escalation", "t1", NodeId.of("ingest"))).isEqualTo(0);
    }

    @Test
    void defaultConstructor_usesInMemoryStore() {
        ProvisionEscalationFaultPolicy defaultPolicy = new ProvisionEscalationFaultPolicy(world);
        DesiredNode node = new DesiredNode(NodeId.of("n"), PipelineNodeTypes.INGESTION,
            new IngestionSpec("d", 10, "json"), HumanGating.NONE);
        DesiredStateGraph graph = new DefaultDesiredStateGraphFactory().of(List.of(node), List.of());
        FaultEvent fault = new FaultEvent(NodeId.of("n"), FaultType.PROVISION_FAILED, "err");

        for (int i = 0; i < 3; i++) {
            assertThat(defaultPolicy.onFault("t1", fault, graph, new ActualState(Map.of()))).isEmpty();
        }
        assertThat(defaultPolicy.onFault("t1", fault, graph, new ActualState(Map.of()))).hasSize(1);
    }
}
