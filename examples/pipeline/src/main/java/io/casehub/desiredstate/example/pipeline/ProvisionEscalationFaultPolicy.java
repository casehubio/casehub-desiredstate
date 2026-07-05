package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three-tier escalation for provision failures:
 * <ol>
 *   <li>Events 1-3: silent retry (no policy action)</li>
 *   <li>Event 4: create AI_REVIEW node for automated diagnosis</li>
 *   <li>Event 5+: if AI review is UNRESOLVED, escalate to HUMAN_REVIEW</li>
 * </ol>
 *
 * Guards against infinite regress by ignoring faults on AI_REVIEW and HUMAN_REVIEW
 * nodes themselves.
 */
public class ProvisionEscalationFaultPolicy implements FaultPolicy {

    private final PipelineWorld world;
    private final Map<NodeId, Integer> faultCounts = new ConcurrentHashMap<>();

    public ProvisionEscalationFaultPolicy(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.PROVISION_FAILED) {
            return List.of();
        }

        // Don't escalate fault-generated nodes (prevents infinite regress)
        DesiredNode faultedNode = current.nodes().get(event.node());
        if (faultedNode != null && (PipelineNodeTypes.AI_REVIEW.equals(faultedNode.type())
                || PipelineNodeTypes.HUMAN_REVIEW.equals(faultedNode.type()))) {
            return List.of();
        }

        int count = faultCounts.merge(event.node(), 1, Integer::sum);

        // Events 1-3: built-in retry — no policy action
        if (count <= 3) {
            return List.of();
        }

        NodeId aiReviewId = NodeId.of("ai-review-" + event.node().value());
        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());

        // Check if HUMAN_REVIEW already exists for target (idempotency)
        if (current.nodes().containsKey(humanReviewId)) {
            return List.of();
        }
        PipelineWorld.ReviewEntry humanReview = world.review(humanReviewId);
        if (humanReview != null) {
            return List.of();
        }

        // Event 4: create AI_REVIEW if it doesn't exist yet
        if (!current.nodes().containsKey(aiReviewId)) {
            DesiredNode reviewNode = new DesiredNode(aiReviewId, PipelineNodeTypes.AI_REVIEW,
                new AiReviewSpec(event.node(), event.detail()), false);
            return List.of(new GraphMutation.AddNode(reviewNode));
        }

        // Event 5+: check review registry for AI outcome
        PipelineWorld.ReviewEntry review = world.review(aiReviewId);
        if (review == null || review.state() == PipelineWorld.ReviewState.PENDING) {
            return List.of();
        }
        if (review.state() == PipelineWorld.ReviewState.RESOLVED) {
            return List.of();
        }

        // UNRESOLVED: escalate to human
        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(event.node(), event.detail(), "AI review could not resolve"), true);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
