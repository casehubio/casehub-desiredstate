package io.casehub.desiredstate.api;

import java.util.Collection;
import java.util.Set;

public interface DesiredStateGraphFactory {
    DesiredStateGraph empty();
    DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps);

    default DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps,
                                 Set<OrderingConstraint> orderingConstraints) {
        return of(nodes, deps).withOrderingConstraints(orderingConstraints);
    }
}
