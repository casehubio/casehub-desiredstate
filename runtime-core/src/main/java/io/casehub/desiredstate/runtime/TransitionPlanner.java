package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepAction;
import io.casehub.desiredstate.api.TransitionPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Plans transitions by comparing desired state against actual state.
 * Produces topologically ordered addition and removal steps.
 */
public class TransitionPlanner {

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual) {
        return plan(desired, actual, null);
    }

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual, DesiredStateGraph previousDesired) {
        List<OrderedStep> removals = new ArrayList<>();

        for (Map.Entry<NodeId, NodeStatus> entry : actual.statuses().entrySet()) {
            NodeId nodeId = entry.getKey();
            if (!desired.nodes().containsKey(nodeId)) {
                boolean remove = switch (entry.getValue()) {
                    case PRESENT, DRIFTED -> true;
                    case ABSENT, UNKNOWN -> false;
                };
                if (remove) {
                    DesiredNode removalNode;
                    if (previousDesired != null && previousDesired.nodes().containsKey(nodeId)) {
                        removalNode = previousDesired.nodes().get(nodeId);
                    } else {
                        removalNode = new DesiredNode(nodeId, new UnknownSpec(), HumanGating.NONE);
                    }
                    removals.add(new OrderedStep(removalNode, StepAction.DEPROVISION));
                }
            }
        }

        Set<NodeId> toAdd = new HashSet<>();
        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeStatus status = actual.statuses().getOrDefault(entry.getKey(), NodeStatus.UNKNOWN);
            boolean provision = switch (status) {
                case ABSENT, UNKNOWN, DRIFTED -> true;
                case PRESENT -> false;
            };
            if (provision) {
                toAdd.add(entry.getKey());
            }
        }

        List<NodeId>      sorted    = topologicalSort(desired, toAdd);
        List<OrderedStep> additions = new ArrayList<>();
        for (NodeId nodeId : sorted) {
            additions.add(new OrderedStep(desired.nodes().get(nodeId), StepAction.PROVISION));
        }

        DesiredStateGraph before = previousDesired != null ? previousDesired : desired;
        return new TransitionPlan(removals, additions, before, desired);
    }


    /**
     * Topologically sorts nodes using Kahn's algorithm.
     * Only considers nodes in the toSort set and their internal dependencies.
     *
     * @param graph  the desired state graph
     * @param toSort the set of node IDs to sort
     * @return a list of node IDs in topological order (roots-first)
     */
    private List<NodeId> topologicalSort(DesiredStateGraph graph, Set<NodeId> toSort) {
        if (toSort.isEmpty()) {
            return List.of();
        }

        // Calculate in-degree for each node in toSort
        Map<NodeId, Integer> inDegree = new HashMap<>();
        for (NodeId nodeId : toSort) {
            inDegree.put(nodeId, 0);
        }

        for (NodeId nodeId : toSort) {
            Set<NodeId> deps = graph.dependenciesOf(nodeId);
            for (NodeId dep : deps) {
                if (toSort.contains(dep)) {
                    inDegree.merge(nodeId, 1, Integer::sum);
                }
            }
        }

        // Queue all nodes with in-degree 0 (roots)
        Queue<NodeId> queue = new ArrayDeque<>();
        for (Map.Entry<NodeId, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<NodeId> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            NodeId current = queue.poll();
            result.add(current);

            // Decrease in-degree for all dependents
            Set<NodeId> dependents = graph.dependentsOf(current);
            for (NodeId dependent : dependents) {
                if (toSort.contains(dependent)) {
                    int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                    if (newDegree == 0) {
                        queue.add(dependent);
                    }
                }
            }
        }

        // If result size doesn't match toSort size, there's a cycle (should not happen in a DAG)
        if (result.size() != toSort.size()) {
            throw new IllegalStateException("Cycle detected in desired state graph");
        }

        return result;
    }

    private static class UnknownSpec implements NodeSpec {
        private static final NodeType UNKNOWN = NodeType.of("unknown");

        @Override
        public NodeType nodeType() {return UNKNOWN;}
    }
}
