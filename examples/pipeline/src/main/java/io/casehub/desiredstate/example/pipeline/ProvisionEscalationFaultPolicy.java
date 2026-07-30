package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.NodeId;

import java.util.List;

public class ProvisionEscalationFaultPolicy implements FaultPolicy {

    private static final String NAMESPACE = "pipeline-escalation";

    private final PipelineWorld   world;
    private final FaultCountStore store;

    public ProvisionEscalationFaultPolicy(PipelineWorld world) {
        this(world, new InMemoryFaultCountStore());
    }

    public ProvisionEscalationFaultPolicy(PipelineWorld world, FaultCountStore store) {
        this.world = world;
        this.store = store;
    }

    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.PROVISION_FAILED) {
            return List.of();
        }

        DesiredNode faultedNode = current.nodes().get(event.node());

        if (faultedNode == null) {
            store.remove(NAMESPACE, tenancyId, event.node());
            return List.of();
        }

        if (PipelineNodeTypes.AI_REVIEW.equals(faultedNode.type())
            || PipelineNodeTypes.HUMAN_REVIEW.equals(faultedNode.type())) {
            return List.of();
        }

        int count = store.incrementAndGet(NAMESPACE, tenancyId, event.node());

        if (count <= 3) {
            return List.of();
        }

        NodeId aiReviewId    = NodeId.of("ai-review-" + event.node().value());
        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());

        if (current.nodes().containsKey(humanReviewId)) {
            return List.of();
        }
        PipelineWorld.ReviewEntry humanReview = world.review(humanReviewId);
        if (humanReview != null) {
            return List.of();
        }

        if (!current.nodes().containsKey(aiReviewId)) {
            DesiredNode reviewNode = new DesiredNode(aiReviewId, PipelineNodeTypes.AI_REVIEW,
                                                     new AiReviewSpec(event.node(), event.detail()), HumanGating.NONE);
            return List.of(new GraphMutation.AddNode(reviewNode));
        }

        PipelineWorld.ReviewEntry review = world.review(aiReviewId);
        if (review == null || review.state() == PipelineWorld.ReviewState.PENDING) {
            return List.of();
        }
        if (review.state() == PipelineWorld.ReviewState.RESOLVED) {
            return List.of();
        }

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
                                                new HumanReviewSpec(event.node(), event.detail(), "AI review could not resolve"), HumanGating.ALL);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
