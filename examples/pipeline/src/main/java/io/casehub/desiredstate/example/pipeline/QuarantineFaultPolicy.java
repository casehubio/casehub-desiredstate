package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;

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

    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
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

        if (current.nodes().containsKey(humanReviewId)) {
            return List.of();
        }

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
                                                new HumanReviewSpec(event.node(), event.detail(), "Quarantined data requires manual review"), HumanGating.ALL);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
