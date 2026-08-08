package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicy {
    List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual);

    static FaultPolicy addReviewNode(NodeType reviewType, ReviewSpecFactory specFactory) {
        return (tenancyId, event, current, actual) -> {
            NodeId reviewId = NodeId.of(reviewType.value() + "-" + event.node().value());
            if (current.nodes().containsKey(reviewId)) {
                return List.of();
            }
            DesiredNode node = new DesiredNode(reviewId, reviewType,
                                               specFactory.create(event, current), HumanGating.ALL);
            return GraphMutations.addNodeDependingOn(node, event.node());
        };
    }

}
