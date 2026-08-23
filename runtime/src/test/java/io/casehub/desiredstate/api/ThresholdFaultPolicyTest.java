package io.casehub.desiredstate.api;

import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThresholdFaultPolicyTest {

    private static final NodeType TARGET = NodeType.of("target");
    private static final NodeType REVIEW = NodeType.of("review");
    private static final NodeType OTHER  = NodeType.of("other");
    private static final ActualState EMPTY_ACTUAL = new ActualState(Map.of());

    private DefaultDesiredStateGraphFactory graphFactory;

    @BeforeEach
    void setUp() {
        graphFactory = new DefaultDesiredStateGraphFactory();
    }


    record TestNodeSpec(NodeType nodeType) implements NodeSpec {}

    record TestReviewSpec(NodeId faultedNode, String reason) implements NodeSpec {
        @Override public NodeType nodeType() { return REVIEW; }
    }

    private ThresholdFaultPolicy policyWithThreshold(int threshold) {
        return ThresholdFaultPolicy.builder()
                .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                .nodeTypes(Set.of(TARGET))
                .tier(threshold, FaultPolicy.addReviewNode(
                        (event, current) -> new TestReviewSpec(event.node(), event.detail())), REVIEW)
                .build();
    }

    private DesiredStateGraph graphWith(String nodeId, NodeType type) {
        return graphFactory.of(
                List.of(new DesiredNode(NodeId.of(nodeId),
                        new TestNodeSpec(type), HumanGating.NONE)),
                List.of());
    }

    @Test
    void belowThreshold_returnsEmpty() {
        var policy = policyWithThreshold(3);
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void atThreshold_delegatesToAction() {
        var policy = policyWithThreshold(3);
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        var mutations = policy.onFault("t1", event, graph, EMPTY_ACTUAL);

        assertThat(mutations).hasSize(2);
        assertThat(mutations.getFirst()).isInstanceOf(GraphMutation.AddNode.class);
        var addNode = (GraphMutation.AddNode) mutations.getFirst();
        assertThat(addNode.node().id()).isEqualTo(NodeId.of("review-n1"));
        assertThat(addNode.node().type()).isEqualTo(REVIEW);
        assertThat(addNode.node().humanGating()).isEqualTo(HumanGating.ALL);
    }

    @Test
    void aboveThreshold_delegatesAgain() {
        var policy = policyWithThreshold(2);
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).hasSize(2);
        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).hasSize(2);
    }

    @Test
    void wrongFaultType_returnsEmpty() {
        var policy = policyWithThreshold(1);
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DEGRADED, "drift");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void wrongNodeType_returnsEmpty() {
        var policy = policyWithThreshold(1);
        var graph = graphWith("n1", OTHER);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void ignoreTypes_regressGuard_returnsEmpty() {
        var policy = policyWithThreshold(1);
        var graph = graphWith("review-n1", REVIEW);
        var event = new FaultEvent(NodeId.of("review-n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void emptyNodeTypes_matchesAll() {
        var policy = ThresholdFaultPolicy.builder()
                .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                .tier(1, FaultPolicy.addReviewNode(
                        (event, current) -> new TestReviewSpec(event.node(), event.detail())), REVIEW)
                .build();
        var graph = graphWith("n1", OTHER);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).hasSize(2);
    }

    @Test
    void multipleNodesTrackedIndependently() {
        var policy = policyWithThreshold(2);
        var node1 = new DesiredNode(NodeId.of("a"), new TestNodeSpec(TARGET), HumanGating.NONE);
        var node2 = new DesiredNode(NodeId.of("b"), new TestNodeSpec(TARGET), HumanGating.NONE);
        var graph = graphFactory.of(List.of(node1, node2), List.of());

        var eventA = new FaultEvent(NodeId.of("a"), FaultType.PROVISION_FAILED, "fail");
        var eventB = new FaultEvent(NodeId.of("b"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", eventA, graph, EMPTY_ACTUAL);
        assertThat(policy.onFault("t1", eventB, graph, EMPTY_ACTUAL)).isEmpty();
        assertThat(policy.onFault("t1", eventA, graph, EMPTY_ACTUAL)).hasSize(2);
    }

    @Test
    void nodeAbsentFromGraph_returnsEmpty() {
        var policy = policyWithThreshold(1);
        var graph = graphFactory.of(List.of(), List.of());
        var event = new FaultEvent(NodeId.of("gone"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void faultCountPersistsAcrossRecovery() {
        var policy = policyWithThreshold(3);
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        var mutations = policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        assertThat(mutations).hasSize(2);
    }

    @Test
    void builderRequiresFaultTypes() {
        assertThatThrownBy(() -> ThresholdFaultPolicy.builder()
                .tier(1, (t, e, g, a) -> List.of(), NodeType.of("x"))
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builderRejectsNoTiers() {
        assertThatThrownBy(() -> ThresholdFaultPolicy.builder()
                .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsZeroThreshold() {
        assertThatThrownBy(() -> ThresholdFaultPolicy.builder()
                                                     .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                     .tier(0, (t, e, g, a) -> List.of(), NodeType.of("x"))
                                                     .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }


    @Test
    void addReviewNode_duplicateGuard() {
        var policy = policyWithThreshold(1);
        var targetNode = new DesiredNode(NodeId.of("n1"), new TestNodeSpec(TARGET), HumanGating.NONE);
        var reviewNode = new DesiredNode(NodeId.of("review-n1"), new TestReviewSpec(NodeId.of("n1"), "prior"), HumanGating.ALL);
        var graph = graphFactory.of(List.of(targetNode, reviewNode), List.of());
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void addReviewNode_returnsDependencyEdge() {
        var policy = policyWithThreshold(1);
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        var mutations = policy.onFault("t1", event, graph, EMPTY_ACTUAL);

        assertThat(mutations).hasSize(2);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
        assertThat(mutations.get(1)).isInstanceOf(GraphMutation.AddDependency.class);
        var addDep = (GraphMutation.AddDependency) mutations.get(1);
        assertThat(addDep.dependency().from()).isEqualTo(NodeId.of("review-n1"));
        assertThat(addDep.dependency().to()).isEqualTo(NodeId.of("n1"));
    }


    @Test
    void tenantIsolation_sameFaultCountedIndependently() {
        var policy = policyWithThreshold(2);
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("tenant-a", event, graph, EMPTY_ACTUAL);
        assertThat(policy.onFault("tenant-b", event, graph, EMPTY_ACTUAL)).isEmpty();
        assertThat(policy.onFault("tenant-a", event, graph, EMPTY_ACTUAL)).hasSize(2);
    }

    @Test
    void customStore_receivesIncrementCalls() {
        var store = new InMemoryFaultCountStore();
        var policy = ThresholdFaultPolicy.builder()
                                         .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                         .tier(2, FaultPolicy.addReviewNode(
                                                 (event, current) -> new TestReviewSpec(event.node(), event.detail())), REVIEW)
                                         .faultCountStore(store)
                                         .namespace("test-policy")
                                         .build();
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);

        assertThat(store.getCount("test-policy", "t1", NodeId.of("n1"))).isEqualTo(1);
    }

    @Test
    void lazyEviction_matchingFaultType_removesCount() {
        var store = new InMemoryFaultCountStore();
        var policy = ThresholdFaultPolicy.builder()
                                         .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                         .tier(3, (t, e, g, a) -> List.of(), NodeType.of("escalation"))
                                         .faultCountStore(store)
                                         .namespace("test")
                                         .build();
        var graph = graphWith("n1", TARGET);
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        assertThat(store.getCount("test", "t1", NodeId.of("n1"))).isEqualTo(1);

        var emptyGraph = graphFactory.of(List.of(), List.of());
        policy.onFault("t1", event, emptyGraph, EMPTY_ACTUAL);

        assertThat(store.getCount("test", "t1", NodeId.of("n1"))).isEqualTo(0);
    }

    @Test
    void lazyEviction_nonMatchingFaultType_stillRemovesCount() {
        var store = new InMemoryFaultCountStore();
        var policy = ThresholdFaultPolicy.builder()
                                         .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                         .tier(3, (t, e, g, a) -> List.of(), NodeType.of("escalation"))
                                         .faultCountStore(store)
                                         .namespace("test")
                                         .build();
        var graph          = graphWith("n1", TARGET);
        var provisionEvent = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", provisionEvent, graph, EMPTY_ACTUAL);
        assertThat(store.getCount("test", "t1", NodeId.of("n1"))).isEqualTo(1);

        var emptyGraph    = graphFactory.of(List.of(), List.of());
        var degradedEvent = new FaultEvent(NodeId.of("n1"), FaultType.NODE_DEGRADED, "drift");
        policy.onFault("t1", degradedEvent, emptyGraph, EMPTY_ACTUAL);

        assertThat(store.getCount("test", "t1", NodeId.of("n1"))).isEqualTo(0);
    }

    @Test
    void resetCount_resetsAndNextFaultsStartFromOne() {
        var policy = policyWithThreshold(3);
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.resetCount("t1", NodeId.of("n1"));

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL))
                .as("Third fault after reset triggers action")
                .hasSize(2);
    }

    @Test
    void builder_requiresNamespaceForCustomStore() {
        assertThatThrownBy(() -> ThresholdFaultPolicy.builder()
                                                     .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                     .tier(1, (t, e, g, a) -> List.of(), NodeType.of("x"))
                                                     .faultCountStore(new InMemoryFaultCountStore())
                                                     .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
    }


    // === Multi-tier tests ===

    private static final NodeType AI_REVIEW    = NodeType.of("ai-review");
    private static final NodeType HUMAN_REVIEW = NodeType.of("human-review");

    record AiReviewSpec(NodeId faultedNode) implements NodeSpec {
        @Override public NodeType nodeType() { return AI_REVIEW; }
    }

    record HumanReviewSpec(NodeId faultedNode) implements NodeSpec {
        @Override public NodeType nodeType() { return HUMAN_REVIEW; }
    }

    private ThresholdFaultPolicy twoTierPolicy() {
        return ThresholdFaultPolicy.builder()
                                   .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                   .nodeTypes(Set.of(TARGET))
                                   .tier(3, FaultPolicy.addReviewNode(
                                                                      (event, current) -> new AiReviewSpec(event.node())), AI_REVIEW)
                                   .tier(6, FaultPolicy.addReviewNode(
                                                                      (event, current) -> new HumanReviewSpec(event.node())), HUMAN_REVIEW)
                                   .build();
    }

    @Test
    void multiTier_belowAllThresholds_returnsEmpty() {
        var policy = twoTierPolicy();
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void multiTier_atTier1Threshold_firesTier1() {
        var policy = twoTierPolicy();
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        var mutations = policy.onFault("t1", event, graph, EMPTY_ACTUAL);

        assertThat(mutations).hasSize(2);
        var addNode = (GraphMutation.AddNode) mutations.get(0);
        assertThat(addNode.node().type()).isEqualTo(AI_REVIEW);
    }

    @Test
    void multiTier_atTier2Threshold_tier1Present_firesTier2() {
        var policy = twoTierPolicy();
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        for (int i = 0; i < 5; i++) {
            policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        }

        var aiNode = new DesiredNode(NodeId.of("ai-review-n1"),
                                     new AiReviewSpec(NodeId.of("n1")), HumanGating.ALL);
        var graphWithAi = graph.withNode(aiNode)
                               .withDependency(new Dependency(NodeId.of("ai-review-n1"), NodeId.of("n1")));

        var mutations = policy.onFault("t1", event, graphWithAi, EMPTY_ACTUAL);

        assertThat(mutations).hasSize(2);
        var addNode = (GraphMutation.AddNode) mutations.get(0);
        assertThat(addNode.node().type()).isEqualTo(HUMAN_REVIEW);
    }

    @Test
    void multiTier_atTier2Threshold_tier1Absent_firesTier1() {
        var policy = twoTierPolicy();
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        for (int i = 0; i < 5; i++) {
            policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        }
        var mutations = policy.onFault("t1", event, graph, EMPTY_ACTUAL);

        assertThat(mutations).hasSize(2);
        var addNode = (GraphMutation.AddNode) mutations.get(0);
        assertThat(addNode.node().type()).isEqualTo(AI_REVIEW);
    }

    @Test
    void multiTier_firstMatchWins_emptyResultNotFallenThrough() {
        var policy = twoTierPolicy();
        var graph  = graphWith("n1", TARGET);
        var event  = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "fail");

        for (int i = 0; i < 3; i++) {
            policy.onFault("t1", event, graph, EMPTY_ACTUAL);
        }

        var aiNode = new DesiredNode(NodeId.of("ai-review-n1"),
                                     new AiReviewSpec(NodeId.of("n1")), HumanGating.ALL);
        var graphWithAi = graph.withNode(aiNode)
                               .withDependency(new Dependency(NodeId.of("ai-review-n1"), NodeId.of("n1")));

        var mutations = policy.onFault("t1", event, graphWithAi, EMPTY_ACTUAL);
        assertThat(mutations).isEmpty();
    }

    @Test
    void multiTier_autoIgnore_faultOnTierNodeType_returnsEmpty() {
        var policy = twoTierPolicy();
        var aiNode = new DesiredNode(NodeId.of("ai-review-n1"),
                                     new AiReviewSpec(NodeId.of("n1")), HumanGating.NONE);
        var graph = graphFactory.of(List.of(aiNode), List.of());
        var event = new FaultEvent(NodeId.of("ai-review-n1"), FaultType.PROVISION_FAILED, "fail");

        assertThat(policy.onFault("t1", event, graph, EMPTY_ACTUAL)).isEmpty();
    }

    @Test
    void multiTier_builderRejectsNonAscendingThresholds() {
        assertThatThrownBy(() -> ThresholdFaultPolicy.builder()
                                                     .faultTypes(Set.of(FaultType.PROVISION_FAILED))
                                                     .tier(5, (t, e, g, a) -> List.of(), AI_REVIEW)
                                                     .tier(3, (t, e, g, a) -> List.of(), HUMAN_REVIEW)
                                                     .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

}
