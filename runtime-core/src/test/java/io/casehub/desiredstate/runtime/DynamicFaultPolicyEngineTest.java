package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicFaultPolicyEngineTest {

    @Test
    void evaluateWithStaticPolicies() {
        var policy = stubPolicy(List.of(
                new GraphMutation.AddNode<>(
                        "review-1",
                        new DesiredNode(NodeId.of("review-1"), new TestSpec(NodeType.of("review")), HumanGating.NONE))));
        var engine = new DynamicFaultPolicyEngine(List.of(policy));

        var mutations = engine.evaluate("t1", faultEvent(), emptyGraph(), new ActualState(java.util.Map.of()));
        assertThat(mutations).hasSize(1);
    }

    @Test
    void addPolicyDynamically() {
        var engine = new DynamicFaultPolicyEngine(List.of());
        assertThat(engine.evaluate("t1", faultEvent(), emptyGraph(), new ActualState(java.util.Map.of()))).isEmpty();

        var policy = stubPolicy(List.of(
                new GraphMutation.AddNode<>(
                        "review-1",
                        new DesiredNode(NodeId.of("review-1"), new TestSpec(NodeType.of("review")), HumanGating.NONE))));
        engine.addPolicy(policy);

        var mutations = engine.evaluate("t1", faultEvent(), emptyGraph(), new ActualState(java.util.Map.of()));
        assertThat(mutations).hasSize(1);
    }

    @Test
    void removePolicyDynamically() {
        var policy = stubPolicy(List.of(
                new GraphMutation.AddNode<>(
                        "review-1",
                        new DesiredNode(NodeId.of("review-1"), new TestSpec(NodeType.of("review")), HumanGating.NONE))));
        var engine = new DynamicFaultPolicyEngine(List.of(policy));
        engine.removePolicy(policy);

        var mutations = engine.evaluate("t1", faultEvent(), emptyGraph(), new ActualState(java.util.Map.of()));
        assertThat(mutations).isEmpty();
    }

    @Test
    void conflictingMutationsThrows() {
        var addNode    = new DesiredNode(NodeId.of("n1"), new TestSpec(NodeType.of("review")), HumanGating.NONE);
        var removeNode = new DesiredNode(NodeId.of("n1"), new TestSpec(NodeType.of("review")), HumanGating.NONE);

        FaultPolicy p1 = (tenancyId, event, current, actual) -> List.of(
                new GraphMutation.AddNode<>("n1", addNode));
        FaultPolicy p2 = (tenancyId, event, current, actual) -> List.of(
                new GraphMutation.RemoveNode<>("n1"));

        var engine = new DynamicFaultPolicyEngine(List.of(p1, p2));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                   () -> engine.evaluate("t1", faultEvent(), emptyGraph(), new ActualState(java.util.Map.of())))
                                       .isInstanceOf(io.casehub.desiredstate.api.ConflictingMutationException.class);
    }


    private record TestSpec(NodeType nodeType) implements NodeSpec {}

    private static FaultEvent faultEvent() {
        return new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "test");
    }

    private static DesiredStateGraph emptyGraph() {
        return new DefaultDesiredStateGraphFactory().empty();
    }

    private static FaultPolicy stubPolicy(List<GraphMutation<DesiredNode>> mutations) {
        return (tenancyId, event, current, actual) -> mutations;
    }
}
