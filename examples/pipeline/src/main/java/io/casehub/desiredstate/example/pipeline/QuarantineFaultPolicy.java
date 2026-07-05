package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.List;

/**
 * Handles quarantined validators: when a validator's quality threshold is breached
 * and data is quarantined, escalates directly to HUMAN_REVIEW for manual inspection.
 *
 * Only activates for QUARANTINED validators — not DEGRADED validators from upstream
 * schema drift (prevents duplicate HUMAN_REVIEW for the same root cause).
 */
public class QuarantineFaultPolicy implements FaultPolicy {

    private final PipelineWorld world;

    public QuarantineFaultPolicy(PipelineWorld world) {
        this.world = world;
    }

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        DesiredNode node = current.nodes().get(event.node());
        if (node == null || !PipelineNodeTypes.VALIDATOR.equals(node.type())) {
            return List.of();
        }

        if (world.stageState(event.node()) != PipelineWorld.StageState.QUARANTINED) {
            return List.of();
        }

        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());

        // Idempotency: don't create if already in graph
        if (current.nodes().containsKey(humanReviewId)) {
            return List.of();
        }

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(event.node(), event.detail(), "Quarantined data requires manual review"), true);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
