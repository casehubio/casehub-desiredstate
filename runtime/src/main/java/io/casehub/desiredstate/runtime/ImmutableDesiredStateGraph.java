package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.*;

import java.util.*;

/**
 * Immutable desired-state graph backed by dual adjacency maps (forward deps + reverse deps),
 * inspired by Clojure's loom adjacency-map pattern. Every mutation returns a new instance
 * with a bumped version; the original is never modified.
 *
 * <p>Package-private — consumers use the {@link DesiredStateGraph} interface.
 */
final class ImmutableDesiredStateGraph implements DesiredStateGraph {

    private final Map<NodeId, DesiredNode> nodes;
    /** forward edges: node → set of nodes it depends on */
    private final Map<NodeId, Set<NodeId>> forwardEdges;
    /** reverse edges: node → set of nodes that depend on it */
    private final Map<NodeId, Set<NodeId>> reverseEdges;
    private final int version;

    ImmutableDesiredStateGraph(
            Map<NodeId, DesiredNode> nodes,
            Map<NodeId, Set<NodeId>> forwardEdges,
            Map<NodeId, Set<NodeId>> reverseEdges,
            int version) {
        this.nodes = Map.copyOf(nodes);
        this.forwardEdges = deepCopyEdges(forwardEdges);
        this.reverseEdges = deepCopyEdges(reverseEdges);
        this.version = version;
    }

    /** Empty graph at version 0. */
    static ImmutableDesiredStateGraph empty() {
        return new ImmutableDesiredStateGraph(Map.of(), Map.of(), Map.of(), 0);
    }

    // --- DesiredStateGraph interface ---

    @Override
    public Map<NodeId, DesiredNode> nodes() {
        return nodes;
    }

    @Override
    public Set<Dependency> dependencies() {
        var deps = new LinkedHashSet<Dependency>();
        for (var entry : forwardEdges.entrySet()) {
            NodeId from = entry.getKey();
            for (NodeId to : entry.getValue()) {
                deps.add(new Dependency(from, to));
            }
        }
        return Set.copyOf(deps);
    }

    @Override
    public Set<NodeId> dependenciesOf(NodeId node) {
        Set<NodeId> deps = forwardEdges.get(node);
        return deps != null ? deps : Set.of();
    }

    @Override
    public Set<NodeId> dependentsOf(NodeId node) {
        Set<NodeId> deps = reverseEdges.get(node);
        return deps != null ? deps : Set.of();
    }

    @Override
    public Set<NodeId> roots() {
        var result = new LinkedHashSet<NodeId>();
        for (NodeId id : nodes.keySet()) {
            Set<NodeId> fwd = forwardEdges.get(id);
            if (fwd == null || fwd.isEmpty()) {
                result.add(id);
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public Set<NodeId> leaves() {
        var result = new LinkedHashSet<NodeId>();
        for (NodeId id : nodes.keySet()) {
            Set<NodeId> rev = reverseEdges.get(id);
            if (rev == null || rev.isEmpty()) {
                result.add(id);
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    // --- Mutation methods (return new instances) ---

    @Override
    public DesiredStateGraph withNode(DesiredNode node) {
        var newNodes = new LinkedHashMap<>(nodes);
        newNodes.put(node.id(), node);
        return new ImmutableDesiredStateGraph(newNodes, forwardEdges, reverseEdges, version + 1);
    }

    @Override
    public DesiredStateGraph withoutNode(NodeId id) {
        var newNodes = new LinkedHashMap<>(nodes);
        newNodes.remove(id);

        var newForward = mutableEdges(forwardEdges);
        var newReverse = mutableEdges(reverseEdges);

        // Remove forward edges from this node
        Set<NodeId> deps = newForward.remove(id);
        if (deps != null) {
            for (NodeId dep : deps) {
                Set<NodeId> revSet = newReverse.get(dep);
                if (revSet != null) {
                    revSet.remove(id);
                    if (revSet.isEmpty()) newReverse.remove(dep);
                }
            }
        }

        // Remove reverse edges to this node (i.e., other nodes that depended on this node)
        Set<NodeId> dependents = newReverse.remove(id);
        if (dependents != null) {
            for (NodeId dependent : dependents) {
                Set<NodeId> fwdSet = newForward.get(dependent);
                if (fwdSet != null) {
                    fwdSet.remove(id);
                    if (fwdSet.isEmpty()) newForward.remove(dependent);
                }
            }
        }

        return new ImmutableDesiredStateGraph(newNodes, newForward, newReverse, version + 1);
    }

    @Override
    public DesiredStateGraph withDependency(Dependency dep) {
        NodeId from = dep.from();
        NodeId to = dep.to();

        // Validate both nodes exist
        if (!nodes.containsKey(from)) {
            throw new DanglingDependencyException(from, to);
        }
        if (!nodes.containsKey(to)) {
            throw new DanglingDependencyException(from, to);
        }

        // Self-loop check
        if (from.equals(to)) {
            throw new CyclicDependencyException(List.of(from, to));
        }

        // Build tentative forward edges and check for cycles
        var newForward = mutableEdges(forwardEdges);
        newForward.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);

        detectCycle(from, to, newForward);

        var newReverse = mutableEdges(reverseEdges);
        newReverse.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(from);

        return new ImmutableDesiredStateGraph(nodes, newForward, newReverse, version + 1);
    }

    @Override
    public DesiredStateGraph withoutDependency(Dependency dep) {
        NodeId from = dep.from();
        NodeId to = dep.to();

        var newForward = mutableEdges(forwardEdges);
        var newReverse = mutableEdges(reverseEdges);

        Set<NodeId> fwdSet = newForward.get(from);
        if (fwdSet != null) {
            fwdSet.remove(to);
            if (fwdSet.isEmpty()) newForward.remove(from);
        }

        Set<NodeId> revSet = newReverse.get(to);
        if (revSet != null) {
            revSet.remove(from);
            if (revSet.isEmpty()) newReverse.remove(to);
        }

        return new ImmutableDesiredStateGraph(nodes, newForward, newReverse, version + 1);
    }

    @Override
    public DesiredStateGraph withMutation(GraphMutation mutation) {
        return switch (mutation) {
            case GraphMutation.AddNode m -> withNode(m.node());
            case GraphMutation.RemoveNode m -> withoutNode(m.id());
            case GraphMutation.UpdateNode m -> {
                if (!nodes.containsKey(m.id())) {
                    throw new IllegalArgumentException(
                            "Cannot update node " + m.id().value() + ": not in graph");
                }
                yield withNode(m.adaptedNode());
            }
            case GraphMutation.AddDependency m -> withDependency(m.dependency());
            case GraphMutation.RemoveDependency m -> withoutDependency(m.dependency());
        };
    }

    @Override
    public DesiredStateGraph overlay(DesiredStateGraph other) {
        // Validate shared nodes have equal specs
        for (var entry : other.nodes().entrySet()) {
            NodeId id = entry.getKey();
            DesiredNode otherNode = entry.getValue();
            DesiredNode thisNode = nodes.get(id);
            if (thisNode != null && !thisNode.spec().equals(otherNode.spec())) {
                throw new IllegalArgumentException(
                        "Overlay conflict for node " + id.value() +
                        ": specs differ — " + thisNode.spec() + " vs " + otherNode.spec());
            }
        }

        // Add nodes from other
        DesiredStateGraph result = this;
        for (DesiredNode node : other.nodes().values()) {
            if (!result.nodes().containsKey(node.id())) {
                result = result.withNode(node);
            }
        }

        // Add edges from other — withDependency() handles cycle detection per edge
        for (Dependency dep : other.dependencies()) {
            if (!result.dependencies().contains(dep)) {
                result = result.withDependency(dep);
            }
        }

        return result;
    }

    @Override
    public DesiredStateGraph connect(DesiredStateGraph other) {
        // First overlay the nodes and edges
        DesiredStateGraph overlaid = overlay(other);

        // Then connect: add edges from leaves of `this` to roots of `other`
        Set<NodeId> thisLeaves = this.leaves();
        Set<NodeId> otherRoots = other.roots();

        DesiredStateGraph result = overlaid;
        for (NodeId leaf : thisLeaves) {
            for (NodeId root : otherRoots) {
                result = result.withDependency(new Dependency(leaf, root));
            }
        }
        return result;
    }

    // --- Cycle detection ---

    /**
     * DFS cycle detection: starting from {@code to}, follow forward edges.
     * If we reach {@code from}, a cycle exists. The new edge is from→to,
     * so a cycle exists if there is already a path from to→...→from.
     */
    private void detectCycle(NodeId from, NodeId to, Map<NodeId, Set<NodeId>> edges) {
        var visited = new LinkedHashSet<NodeId>();
        var stack = new ArrayDeque<NodeId>();
        stack.push(to);

        while (!stack.isEmpty()) {
            NodeId current = stack.pop();
            if (current.equals(from)) {
                // Build cycle path: from → ... → to → from
                var cyclePath = new ArrayList<NodeId>();
                cyclePath.add(from);
                // Reconstruct path from 'from' to 'to' via BFS for a clean cycle
                cyclePath.addAll(findPath(to, from, edges));
                throw new CyclicDependencyException(cyclePath);
            }
            if (visited.add(current)) {
                Set<NodeId> next = edges.get(current);
                if (next != null) {
                    for (NodeId n : next) {
                        if (!visited.contains(n)) {
                            stack.push(n);
                        }
                    }
                }
            }
        }
    }

    /** BFS to find a path from start to end following forward edges. */
    private List<NodeId> findPath(NodeId start, NodeId end, Map<NodeId, Set<NodeId>> edges) {
        var parents = new LinkedHashMap<NodeId, NodeId>();
        var queue = new ArrayDeque<NodeId>();
        queue.add(start);
        parents.put(start, null);

        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            if (current.equals(end)) {
                var path = new ArrayList<NodeId>();
                for (NodeId n = end; n != null; n = parents.get(n)) {
                    path.add(n);
                }
                Collections.reverse(path);
                return path;
            }
            Set<NodeId> next = edges.get(current);
            if (next != null) {
                for (NodeId n : next) {
                    if (!parents.containsKey(n)) {
                        parents.put(n, current);
                        queue.add(n);
                    }
                }
            }
        }
        // Should not reach here if cycle was detected
        return List.of(start, end);
    }

    // --- Internal helpers ---

    private static Map<NodeId, Set<NodeId>> deepCopyEdges(Map<NodeId, Set<NodeId>> edges) {
        if (edges.isEmpty()) return Map.of();
        var copy = new LinkedHashMap<NodeId, Set<NodeId>>();
        for (var entry : edges.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static Map<NodeId, Set<NodeId>> mutableEdges(Map<NodeId, Set<NodeId>> edges) {
        var copy = new LinkedHashMap<NodeId, Set<NodeId>>();
        for (var entry : edges.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return copy;
    }
}
