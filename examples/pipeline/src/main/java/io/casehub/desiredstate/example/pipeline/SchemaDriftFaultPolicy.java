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
 * Handles schema drift: when a SCHEMA node reports NODE_DEGRADED (version mismatch
 * between declared schema and actual data), creates a HUMAN_REVIEW for approval.
 *
 * Does NOT return RemoveNode for downstream stages — the pipeline continues operating
 * with the existing schema while the human reviews the drift. Downstream stages will
 * report DEGRADED via the adapter, but removal is not this policy's responsibility.
 */
public class SchemaDriftFaultPolicy implements FaultPolicy {

    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        DesiredNode node = current.nodes().get(event.node());
        if (node == null || !PipelineNodeTypes.SCHEMA.equals(node.type())) {
            return List.of();
        }

        NodeId humanReviewId = NodeId.of("human-review-" + event.node().value());

        if (current.nodes().containsKey(humanReviewId)) {
            return List.of();
        }

        DesiredNode humanNode = new DesiredNode(humanReviewId, PipelineNodeTypes.HUMAN_REVIEW,
                                                new HumanReviewSpec(event.node(), event.detail(), "Schema drift requires approval"), HumanGating.ALL);
        return List.of(new GraphMutation.AddNode(humanNode));
    }
}
