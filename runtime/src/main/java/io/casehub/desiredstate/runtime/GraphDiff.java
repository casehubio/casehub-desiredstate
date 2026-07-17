package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class GraphDiff {

    private GraphDiff() {}

    static List<GraphMutation> computeMutations(DesiredStateGraph current, DesiredStateGraph adapted) {
        List<GraphMutation> mutations = new ArrayList<>();

        Set<NodeType> adaptedTypes = new HashSet<>();
        for (DesiredNode node : adapted.nodes().values()) {
            adaptedTypes.add(node.type());
        }

        for (Map.Entry<NodeId, DesiredNode> entry : adapted.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode adaptedNode = entry.getValue();
            DesiredNode currentNode = current.nodes().get(id);

            if (currentNode == null) {
                mutations.add(new GraphMutation.AddNode(adaptedNode));
            } else if (!currentNode.equals(adaptedNode)) {
                mutations.add(new GraphMutation.UpdateNode(id, adaptedNode));
            }
        }

        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode currentNode = entry.getValue();
            if (adaptedTypes.contains(currentNode.type()) && !adapted.nodes().containsKey(id)) {
                mutations.add(new GraphMutation.RemoveNode(id));
            }
        }

        Set<NodeId> allKnownNodes = new HashSet<>();
        allKnownNodes.addAll(current.nodes().keySet());
        allKnownNodes.addAll(adapted.nodes().keySet());

        for (Dependency dep : adapted.dependencies()) {
            if (!current.dependencies().contains(dep)) {
                if (allKnownNodes.contains(dep.from()) && allKnownNodes.contains(dep.to())) {
                    mutations.add(new GraphMutation.AddDependency(dep));
                }
            }
        }

        Set<NodeId> inScopeNodeIds = new HashSet<>();
        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            if (adaptedTypes.contains(entry.getValue().type())) {
                inScopeNodeIds.add(entry.getKey());
            }
        }
        inScopeNodeIds.addAll(adapted.nodes().keySet());

        for (Dependency dep : current.dependencies()) {
            if (inScopeNodeIds.contains(dep.from()) && inScopeNodeIds.contains(dep.to())) {
                if (!adapted.dependencies().contains(dep)) {
                    mutations.add(new GraphMutation.RemoveDependency(dep));
                }
            }
        }

        return mutations;
    }

    static NodeId targetNodeId(GraphMutation mutation) {
        return switch (mutation) {
            case GraphMutation.AddNode add -> add.node().id();
            case GraphMutation.RemoveNode remove -> remove.id();
            case GraphMutation.UpdateNode update -> update.id();
            case GraphMutation.AddDependency ignored -> null;
            case GraphMutation.RemoveDependency ignored -> null;
        };
    }
}
