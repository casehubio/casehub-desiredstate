package io.casehub.desiredstate.example.pipeline;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;

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


    private final FaultPolicy reviewPolicy = FaultPolicy.addReviewNode(
            PipelineNodeTypes.HUMAN_REVIEW,
            (event, graph) -> new HumanReviewSpec(event.node(), event.detail(), "Schema drift requires approval"));

    public List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual) {
        if (event.type() != FaultType.NODE_DEGRADED) {
            return List.of();
        }

        DesiredNode node = current.nodes().get(event.node());
        if (node == null || !PipelineNodeTypes.SCHEMA.equals(node.type())) {
            return List.of();
        }

        return reviewPolicy.onFault(tenancyId, event, current, actual);
    }
}
