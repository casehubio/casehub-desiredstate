package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicy {
    List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual);

    static FaultPolicy addReviewNode(ReviewSpecFactory specFactory) {
        return (tenancyId, event, current, actual) -> {
            NodeSpec reviewSpec = specFactory.create(event, current);
            NodeType reviewType = reviewSpec.nodeType();
            NodeId   reviewId   = NodeId.of(reviewType.value() + "-" + event.node().value());
            if (current.nodes().containsKey(reviewId)) {
                return List.of();
            }
            DesiredNode node = new DesiredNode(reviewId, reviewSpec, HumanGating.ALL);
            return GraphMutations.addNodeDependingOn(node, event.node());
        };
    }

}
