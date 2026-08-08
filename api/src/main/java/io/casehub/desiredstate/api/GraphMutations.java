package io.casehub.desiredstate.api;

import java.util.List;

public final class GraphMutations {
    private GraphMutations() {}

    public static List<GraphMutation> addNodeDependingOn(DesiredNode node, NodeId dependsOn) {
        return List.of(
            new GraphMutation.AddNode(node),
            new GraphMutation.AddDependency(new Dependency(node.id(), dependsOn))
        );
    }
}
