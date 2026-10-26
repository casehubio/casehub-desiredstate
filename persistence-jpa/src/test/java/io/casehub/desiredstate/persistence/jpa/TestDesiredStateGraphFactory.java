package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderingConstraint;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class TestDesiredStateGraphFactory implements DesiredStateGraphFactory {

    @Override
    public DesiredStateGraph empty() {
        return new SimpleGraph(Map.of(), Set.of());
    }

    @Override
    public DesiredStateGraph of(Collection<DesiredNode> nodes, Collection<Dependency> deps) {
        DesiredStateGraph g = empty();
        for (DesiredNode n : nodes) g = g.withNode(n);
        for (Dependency d : deps) g = g.withDependency(d);
        return g;
    }

    static class SimpleGraph implements DesiredStateGraph {
        private final Map<NodeId, DesiredNode> nodes;
        private final Set<Dependency>          deps;
        private final Set<OrderingConstraint>  orderingConstraints;

        SimpleGraph(Map<NodeId, DesiredNode> nodes, Set<Dependency> deps) {
            this(nodes, deps, Set.of());
        }

        SimpleGraph(Map<NodeId, DesiredNode> nodes, Set<Dependency> deps, Set<OrderingConstraint> orderingConstraints) {
            this.nodes               = Map.copyOf(nodes);
            this.deps                = Set.copyOf(deps);
            this.orderingConstraints = Set.copyOf(orderingConstraints);
        }

        @Override
        public Map<NodeId, DesiredNode> nodes()              {return nodes;}

        @Override
        public Set<Dependency> dependencies()                {return deps;}

        @Override
        public Set<OrderingConstraint> orderingConstraints() {return orderingConstraints;}

        @Override
        public DesiredStateGraph withOrderingConstraints(Set<OrderingConstraint> constraints) {
            return new SimpleGraph(nodes, deps, constraints);
        }

        @Override
        public Set<NodeId> dependenciesOf(NodeId node) {
            Set<NodeId> result = new HashSet<>();
            for (Dependency d : deps) {
                if (d.from().equals(node)) {result.add(d.to());}
            }
            return result;
        }

        @Override
        public Set<NodeId> dependentsOf(NodeId node) {
            Set<NodeId> result = new HashSet<>();
            for (Dependency d : deps) {
                if (d.to().equals(node)) {result.add(d.from());}
            }
            return result;
        }

        @Override
        public Set<NodeId> roots() {
            Set<NodeId> nonRoots = new HashSet<>();
            for (Dependency d : deps) {nonRoots.add(d.from());}
            Set<NodeId> result = new HashSet<>(nodes.keySet());
            result.removeAll(nonRoots);
            return result;
        }

        @Override
        public Set<NodeId> leaves() {
            Set<NodeId> nonLeaves = new HashSet<>();
            for (Dependency d : deps) {nonLeaves.add(d.to());}
            Set<NodeId> result = new HashSet<>(nodes.keySet());
            result.removeAll(nonLeaves);
            return result;
        }

        @Override
        public int version()     {return 0;}

        @Override
        public boolean isEmpty() {return nodes.isEmpty();}

        @Override
        public DesiredStateGraph withNode(DesiredNode node) {
            var newNodes = new LinkedHashMap<>(nodes);
            newNodes.put(node.id(), node);
            return new SimpleGraph(newNodes, deps, orderingConstraints);
        }

        @Override
        public DesiredStateGraph withoutNode(NodeId id) {
            var newNodes = new LinkedHashMap<>(nodes);
            newNodes.remove(id);
            var newDeps = new LinkedHashSet<Dependency>();
            for (Dependency d : deps) {
                if (!d.from().equals(id) && !d.to().equals(id)) {newDeps.add(d);}
            }
            return new SimpleGraph(newNodes, newDeps, orderingConstraints);
        }

        @Override
        public DesiredStateGraph withDependency(Dependency dep) {
            var newDeps = new LinkedHashSet<>(deps);
            newDeps.add(dep);
            return new SimpleGraph(nodes, newDeps, orderingConstraints);
        }

        @Override
        public DesiredStateGraph withoutDependency(Dependency dep) {
            var newDeps = new LinkedHashSet<>(deps);
            newDeps.remove(dep);
            return new SimpleGraph(nodes, newDeps, orderingConstraints);
        }

        @Override
        public DesiredStateGraph withMutation(GraphMutation<DesiredNode> mutation) {
            return switch (mutation) {
                case GraphMutation.AddNode<DesiredNode> add -> withNode(add.node());
                case GraphMutation.RemoveNode<DesiredNode> rem -> withoutNode(NodeId.of(rem.id()));
                case GraphMutation.AddEdge<DesiredNode> edge -> withDependency(new Dependency(NodeId.of(edge.from()), NodeId.of(edge.to())));
                case GraphMutation.RemoveEdge<DesiredNode> edge -> withoutDependency(new Dependency(NodeId.of(edge.from()), NodeId.of(edge.to())));
                case GraphMutation.UpdateNode<DesiredNode> upd -> withNode(upd.adaptedNode());
            };
        }

        @Override
        public DesiredStateGraph overlay(DesiredStateGraph other) {
            DesiredStateGraph result = this;
            for (DesiredNode n : other.nodes().values()) {result = result.withNode(n);}
            for (Dependency d : other.dependencies()) {result = result.withDependency(d);}
            return result;
        }

        @Override
        public DesiredStateGraph connect(DesiredStateGraph other) {
            return overlay(other);
        }
    }
}
