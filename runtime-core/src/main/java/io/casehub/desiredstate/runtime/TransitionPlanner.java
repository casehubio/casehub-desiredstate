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
import io.casehub.desiredstate.api.TargetStatus;
import io.casehub.desiredstate.api.TransitionPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class TransitionPlanner {

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual) {
        return plan(desired, actual, null, type -> false);
    }

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual, DesiredStateGraph previousDesired) {
        return plan(desired, actual, previousDesired, type -> false);
    }

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual,
                               DesiredStateGraph previousDesired,
                               java.util.function.Predicate<NodeType> supportsStateful) {
        List<OrderedStep> removals = new ArrayList<>();

        for (Map.Entry<NodeId, NodeStatus> entry : actual.statuses().entrySet()) {
            NodeId nodeId = entry.getKey();
            if (!desired.nodes().containsKey(nodeId)) {
                boolean remove = switch (entry.getValue()) {
                    case PRESENT, DRIFTED, SUSPENDED -> true;
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

        Set<NodeId> toAdd     = new HashSet<>();
        Set<NodeId> toSuspend = new HashSet<>();
        Set<NodeId> toResume  = new HashSet<>();

        for (Map.Entry<NodeId, DesiredNode> entry : desired.nodes().entrySet()) {
            NodeId      nodeId = entry.getKey();
            DesiredNode node   = entry.getValue();
            NodeStatus  status = actual.statuses().getOrDefault(nodeId, NodeStatus.UNKNOWN);

            StepAction action = decideAction(status, node.targetStatus());
            if (action == null) {continue;}

            if ((action == StepAction.SUSPEND || action == StepAction.RESUME)
                && !supportsStateful.test(node.type())) {
                action = (action == StepAction.SUSPEND) ? StepAction.DEPROVISION : StepAction.PROVISION;
            }

            switch (action) {
                case PROVISION -> toAdd.add(nodeId);
                case DEPROVISION -> removals.add(new OrderedStep(node, StepAction.DEPROVISION));
                case SUSPEND -> toSuspend.add(nodeId);
                case RESUME -> toResume.add(nodeId);
            }
        }

        List<NodeId>      sortedAdditions = topologicalSort(desired, toAdd);
        List<OrderedStep> additions       = new ArrayList<>();
        for (NodeId nodeId : sortedAdditions) {
            additions.add(new OrderedStep(desired.nodes().get(nodeId), StepAction.PROVISION));
        }

        List<NodeId>      sortedResumptions = topologicalSort(desired, toResume);
        List<OrderedStep> resumptions       = new ArrayList<>();
        for (NodeId nodeId : sortedResumptions) {
            resumptions.add(new OrderedStep(desired.nodes().get(nodeId), StepAction.RESUME));
        }

        List<NodeId>      sortedSuspensions = topologicalSortReverse(desired, toSuspend);
        List<OrderedStep> suspensions       = new ArrayList<>();
        for (NodeId nodeId : sortedSuspensions) {
            suspensions.add(new OrderedStep(desired.nodes().get(nodeId), StepAction.SUSPEND));
        }

        DesiredStateGraph before = previousDesired != null ? previousDesired : desired;
        return new TransitionPlan(removals, suspensions, resumptions, additions, before, desired);
    }

    private StepAction decideAction(NodeStatus actual, TargetStatus target) {
        return switch (target) {
            case ACTIVE -> switch (actual) {
                case PRESENT -> null;
                case ABSENT, UNKNOWN, DRIFTED -> StepAction.PROVISION;
                case SUSPENDED -> StepAction.RESUME;
            };
            case SUSPENDED -> switch (actual) {
                case SUSPENDED -> null;
                case PRESENT, DRIFTED -> StepAction.SUSPEND;
                case ABSENT, UNKNOWN -> StepAction.RESUME;
            };
        };
    }

    private List<NodeId> topologicalSort(DesiredStateGraph graph, Set<NodeId> toSort) {
        if (toSort.isEmpty()) {
            return List.of();
        }

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

        if (result.size() != toSort.size()) {
            throw new IllegalStateException("Cycle detected in desired state graph");
        }

        return result;
    }

    private List<NodeId> topologicalSortReverse(DesiredStateGraph graph, Set<NodeId> toSort) {
        List<NodeId> sorted = topologicalSort(graph, toSort);
        java.util.Collections.reverse(sorted);
        return sorted;
    }

    private static class UnknownSpec implements NodeSpec {
        private static final NodeType UNKNOWN = NodeType.of("unknown");

        @Override
        public NodeType nodeType() {return UNKNOWN;}
    }
}
