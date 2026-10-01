package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Set;

public interface DesiredStateGraph {
    Map<NodeId, DesiredNode> nodes();
    Set<Dependency> dependencies();
    Set<NodeId> dependenciesOf(NodeId node);
    Set<NodeId> dependentsOf(NodeId node);
    Set<NodeId> roots();
    Set<NodeId> leaves();
    int version();
    boolean isEmpty();
    DesiredStateGraph withNode(DesiredNode node);
    DesiredStateGraph withoutNode(NodeId id);
    DesiredStateGraph withDependency(Dependency dep);
    DesiredStateGraph withoutDependency(Dependency dep);

    DesiredStateGraph withMutation(GraphMutation<DesiredNode> mutation);
    DesiredStateGraph overlay(DesiredStateGraph other);
    DesiredStateGraph connect(DesiredStateGraph other);


    default Set<OrderingConstraint> orderingConstraints() {
        return Set.of();
    }

    default DesiredStateGraph withOrderingConstraints(Set<OrderingConstraint> constraints) {
        throw new UnsupportedOperationException("withOrderingConstraints not supported by this implementation");
    }

    default DesiredStateGraph filterByTypes(Set<NodeType> types) {
        DesiredStateGraph result = this;
        for (Map.Entry<NodeId, DesiredNode> entry : nodes().entrySet()) {
            if (!types.contains(entry.getValue().type())) {
                result = result.withoutNode(entry.getKey());
            }
        }
        return result;
    }
}
