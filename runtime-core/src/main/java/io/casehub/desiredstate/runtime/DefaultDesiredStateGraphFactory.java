package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;
import io.quarkus.arc.DefaultBean;
import jakarta.inject.Singleton;

import java.util.Collection;

/**
 * Default CDI-managed factory for creating {@link DesiredStateGraph} instances.
 * Returns {@link ImmutableDesiredStateGraph} backed by dual adjacency maps.
 */
@DefaultBean
@Singleton
public class DefaultDesiredStateGraphFactory implements DesiredStateGraphFactory {

    @Override
    public DesiredStateGraph empty() {
        return ImmutableDesiredStateGraph.empty();
    }

    @Override
    public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) {
        DesiredStateGraph graph = empty();
        for (DesiredNode node : nodes) {
            graph = graph.withNode(node);
        }
        for (Dependency dep : deps) {
            if (!graph.nodes().containsKey(dep.from()) || !graph.nodes().containsKey(dep.to())) {
                throw new DanglingDependencyException(dep.from(), dep.to());
            }
            graph = graph.withDependency(dep);
        }
        return graph;
    }
}
