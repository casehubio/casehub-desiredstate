package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.*;

import java.util.List;

/**
 * Handles schema drift: when a SCHEMA node reports NODE_DEGRADED (version mismatch
 * between declared schema and actual data), creates a HUMAN_REVIEW for approval.
 *
 * Does NOT return RemoveNode for downstream stages — the pipeline continues operating
 * with the existing schema while the human reviews the drift. Downstream stages will
 * report DEGRADED via the adapter, but removal is not this policy's responsibility.
 */
public class SchemaDriftFaultPolicy implements FaultPolicy {

    @Override
    public List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        DesiredNode node = current.nodes().get(event.node());
        if (node == null || !PipelineNodeTypes.SCHEMA.equals(node.type())) {
            return List.of();
        }

        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());

        // Idempotency: don't create if already in graph
        if (current.nodes().containsKey(humanReviewId)) {
            return List.of();
        }

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
            new HumanReviewSpec(event.node(), event.detail(), "Schema drift requires approval"), true);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
