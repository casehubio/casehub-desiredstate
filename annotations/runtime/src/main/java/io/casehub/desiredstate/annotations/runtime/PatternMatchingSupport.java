package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PatternMatchingSupport {

    private PatternMatchingSupport() {}

    public static DesiredNode resolveReference(PatternParameterDescriptor p, int paramIndex,
            String[] paramNames, Map<String, DesiredNode> bindings) {
        if (!p.of().isEmpty()) {
            return bindings.get(p.of());
        }
        for (int i = paramIndex - 1; i >= 0; i--) {
            DesiredNode prev = bindings.get(paramNames[i]);
            if (prev != null) return prev;
        }
        return null;
    }

    public static List<DesiredNode> findDirectNeighbors(DesiredStateGraph graph,
                                                        DesiredNode refNode, PatternParameterDescriptor p) {
        boolean  wildcard   = "*".equals(p.nodeType());
        NodeType targetType = wildcard ? null : NodeType.of(p.nodeType());
        Set<NodeId> neighbors = p.direction() == Direction.DEPENDENCIES
                                ? graph.dependenciesOf(refNode.id())
                                : graph.dependentsOf(refNode.id());
        return neighbors.stream()
                        .map(id -> graph.nodes().get(id))
                        .filter(n -> n != null && (wildcard || n.type().equals(targetType)))
                        .toList();
    }

    public static List<DesiredNode> findReachable(DesiredStateGraph graph,
                                                  DesiredNode refNode, PatternParameterDescriptor p) {
        boolean            wildcard   = "*".equals(p.nodeType());
        NodeType           targetType = wildcard ? null : NodeType.of(p.nodeType());
        List<DesiredNode>  found      = new ArrayList<>();
        Set<NodeId>        visited    = new HashSet<>();
        ArrayDeque<NodeId> queue      = new ArrayDeque<>();
        queue.add(refNode.id());
        visited.add(refNode.id());

        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            Set<NodeId> neighbors = p.direction() == Direction.DEPENDENCIES
                                    ? graph.dependenciesOf(current)
                                    : graph.dependentsOf(current);
            for (NodeId neighbor : neighbors) {
                if (visited.add(neighbor)) {
                    DesiredNode node = graph.nodes().get(neighbor);
                    if (node != null && (wildcard || node.type().equals(targetType))) {
                        found.add(node);
                    }
                    queue.add(neighbor);
                }
            }
        }
        return found;
    }

    public static boolean existsGlobal(DesiredStateGraph graph, PatternParameterDescriptor p) {
        if ("*".equals(p.nodeType())) {
            return !graph.nodes().isEmpty();
        }
        NodeType targetType = NodeType.of(p.nodeType());
        return graph.nodes().values().stream().anyMatch(n -> n.type().equals(targetType));
    }

    public static boolean existsRelational(DesiredStateGraph graph, DesiredNode refNode,
                                           PatternParameterDescriptor p) {
        boolean  wildcard   = "*".equals(p.nodeType());
        NodeType targetType = wildcard ? null : NodeType.of(p.nodeType());
        Set<NodeId> neighbors = p.direction() == Direction.DEPENDENCIES
                                ? graph.dependenciesOf(refNode.id())
                                : graph.dependentsOf(refNode.id());
        return neighbors.stream()
                        .map(id -> graph.nodes().get(id))
                        .anyMatch(n -> n != null && (wildcard || n.type().equals(targetType)));
    }

    public static String[] getParameterNames(Method method) {
        var params = method.getParameters();
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            names[i] = params[i].getName();
        }
        return names;
    }

    public static List<List<DesiredNode>> crossProduct(List<List<DesiredNode>> sets) {
        List<List<DesiredNode>> result = new ArrayList<>();
        result.add(List.of());
        for (List<DesiredNode> set : sets) {
            List<List<DesiredNode>> newResult = new ArrayList<>();
            for (List<DesiredNode> existing : result) {
                for (DesiredNode item : set) {
                    List<DesiredNode> combined = new ArrayList<>(existing);
                    combined.add(item);
                    newResult.add(combined);
                }
            }
            result = newResult;
        }
        return result;
    }
}
