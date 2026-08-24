package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicy {
    List<GraphMutation> onFault(String tenancyId, FaultEvent event, DesiredStateGraph current, ActualState actual);

    static TypedFaultPolicy addReviewNode(ReviewSpecFactory specFactory) {
        NodeType reviewType = specFactory.nodeType();
        return new TypedFaultPolicy() {
            @Override public NodeType outputNodeType() { return reviewType; }
            @Override public List<GraphMutation> onFault(String tenancyId, FaultEvent event,
                    DesiredStateGraph current, ActualState actual) {
                NodeSpec reviewSpec = specFactory.create(event, current);
                if (!reviewSpec.nodeType().equals(reviewType)) {
                    throw new IllegalStateException(
                        "ReviewSpecFactory.nodeType() probe returned " + reviewType
                        + " but create() produced spec with nodeType " + reviewSpec.nodeType()
                        + " — factory must return a consistent NodeType");
                }
                NodeId   reviewId   = NodeId.of(reviewType.value() + "-" + event.node().value());
                if (current.nodes().containsKey(reviewId)) {
                    return List.of();
                }
                DesiredNode node = new DesiredNode(reviewId, reviewSpec, HumanGating.ALL);
                return GraphMutations.addNodeDependingOn(node, event.node());
            }
        };
    }

}
