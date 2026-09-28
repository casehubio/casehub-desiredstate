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
import java.util.Set;

final class GraphDiff {

    private GraphDiff() {}

    static List<GraphMutation<DesiredNode>> computeMutations(DesiredStateGraph current, DesiredStateGraph adapted) {
        List<GraphMutation<DesiredNode>> mutations = new ArrayList<>();

        Set<NodeType> adaptedTypes = new HashSet<>();
        for (DesiredNode node : adapted.nodes().values()) {
            adaptedTypes.add(node.type());
        }

        for (Map.Entry<NodeId, DesiredNode> entry : adapted.nodes().entrySet()) {
            NodeId      id          = entry.getKey();
            DesiredNode adaptedNode = entry.getValue();
            DesiredNode currentNode = current.nodes().get(id);

            if (currentNode == null) {
                mutations.add(new GraphMutation.AddNode<>(adaptedNode.id().value(), adaptedNode));
            } else if (!currentNode.equals(adaptedNode)) {
                mutations.add(new GraphMutation.UpdateNode<>(id.value(), adaptedNode));
            }
        }

        for (Map.Entry<NodeId, DesiredNode> entry : current.nodes().entrySet()) {
            NodeId      id          = entry.getKey();
            DesiredNode currentNode = entry.getValue();
            if (adaptedTypes.contains(currentNode.type()) && !adapted.nodes().containsKey(id)) {
                mutations.add(new GraphMutation.RemoveNode<>(id.value()));
            }
        }

        Set<NodeId> allKnownNodes = new HashSet<>();
        allKnownNodes.addAll(current.nodes().keySet());
        allKnownNodes.addAll(adapted.nodes().keySet());

        for (Dependency dep : adapted.dependencies()) {
            if (!current.dependencies().contains(dep)) {
                if (allKnownNodes.contains(dep.from()) && allKnownNodes.contains(dep.to())) {
                    mutations.add(new GraphMutation.AddEdge<>(dep.from().value(), dep.to().value()));
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
                    mutations.add(new GraphMutation.RemoveEdge<>(dep.from().value(), dep.to().value()));
                }
            }
        }

        return mutations;
    }

    static String targetNodeId(GraphMutation<?> mutation) {
        if (mutation instanceof GraphMutation.AddNode<?> add && add.node() instanceof DesiredNode dn) {
            return dn.id().value();
        }
        return mutation.targetNodeId();
    }
}
