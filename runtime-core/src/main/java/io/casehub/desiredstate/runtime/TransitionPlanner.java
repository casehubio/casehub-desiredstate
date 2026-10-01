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
        return plan(desired, actual, null, type -> false, Set.of());
    }

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual, DesiredStateGraph previousDesired) {
        return plan(desired, actual, previousDesired, type -> false, Set.of());
    }

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual,
                               DesiredStateGraph previousDesired,
                               java.util.function.Predicate<NodeType> supportsStateful) {
        return plan(desired, actual, previousDesired, supportsStateful, Set.of());
    }

    public TransitionPlan plan(DesiredStateGraph desired, ActualState actual,
                               DesiredStateGraph previousDesired,
                               java.util.function.Predicate<NodeType> supportsStateful,
                               Set<NodeId> exemptNodes) {
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
            if (status == NodeStatus.DRIFTED && exemptNodes.contains(nodeId)) {continue;}

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

        DesiredStateGraph before = previousDesired != null ? previousDesired : desired;

        boolean hasEdges       = !desired.dependencies().isEmpty();
        boolean hasConstraints = !desired.orderingConstraints().isEmpty();
        if (!hasEdges && !hasConstraints) {
            List<OrderedStep> addSteps = toAdd.stream()
                                              .map(id -> new OrderedStep(desired.nodes().get(id), StepAction.PROVISION)).toList();
            List<OrderedStep> suspendSteps = toSuspend.stream()
                                                      .map(id -> new OrderedStep(desired.nodes().get(id), StepAction.SUSPEND)).toList();
            List<OrderedStep> resumeSteps = toResume.stream()
                                                    .map(id -> new OrderedStep(desired.nodes().get(id), StepAction.RESUME)).toList();
            return new TransitionPlan(
                    List.of(removals),
                    suspendSteps.isEmpty() ? List.of() : List.of(suspendSteps),
                    resumeSteps.isEmpty() ? List.of() : List.of(resumeSteps),
                    addSteps.isEmpty() ? List.of() : List.of(addSteps),
                    before, desired);
        }

        List<List<OrderedStep>> additionLayers = toLayers(topologicalSort(desired, toAdd),
                                                          desired, StepAction.PROVISION);
        List<List<OrderedStep>> resumptionLayers = toLayers(topologicalSort(desired, toResume),
                                                            desired, StepAction.RESUME);
        List<List<OrderedStep>> suspensionLayers = toLayers(topologicalSortReverse(desired, toSuspend),
                                                            desired, StepAction.SUSPEND);

        return new TransitionPlan(List.of(removals), suspensionLayers, resumptionLayers,
                                  additionLayers, before, desired);
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


    private List<List<OrderedStep>> toLayers(List<List<NodeId>> nodeLayers,
                                             DesiredStateGraph graph, StepAction action) {
        List<List<OrderedStep>> result = new ArrayList<>();
        for (List<NodeId> layer : nodeLayers) {
            List<OrderedStep> steps = new ArrayList<>();
            for (NodeId nodeId : layer) {
                steps.add(new OrderedStep(graph.nodes().get(nodeId), action));
            }
            result.add(steps);
        }
        return result;
    }

    private List<List<NodeId>> topologicalSort(DesiredStateGraph graph, Set<NodeId> toSort) {
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

        Map<NodeId, Set<NodeId>> virtualReverse = new HashMap<>();
        for (io.casehub.desiredstate.api.OrderingConstraint c : graph.orderingConstraints()) {
            Set<NodeId> beforeNodes = nodesOfType(toSort, graph, c.before());
            Set<NodeId> afterNodes  = nodesOfType(toSort, graph, c.after());
            for (NodeId afterNode : afterNodes) {
                inDegree.merge(afterNode, beforeNodes.size(), Integer::sum);
            }
            for (NodeId beforeNode : beforeNodes) {
                virtualReverse.computeIfAbsent(beforeNode, k -> new HashSet<>()).addAll(afterNodes);
            }
        }

        Queue<NodeId> queue = new ArrayDeque<>();
        for (Map.Entry<NodeId, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<List<NodeId>> layers    = new ArrayList<>();
        int                processed = 0;
        while (!queue.isEmpty()) {
            List<NodeId> layer = new ArrayList<>(queue);
            queue.clear();
            layers.add(layer);
            processed += layer.size();

            for (NodeId current : layer) {
                Set<NodeId> dependents = graph.dependentsOf(current);
                for (NodeId dependent : dependents) {
                    if (toSort.contains(dependent)) {
                        int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                        if (newDegree == 0) {
                            queue.add(dependent);
                        }
                    }
                }
                Set<NodeId> virtualDeps = virtualReverse.getOrDefault(current, Set.of());
                for (NodeId dependent : virtualDeps) {
                    if (toSort.contains(dependent)) {
                        int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                        if (newDegree == 0) {
                            queue.add(dependent);
                        }
                    }
                }
            }
        }

        if (processed != toSort.size()) {
            throw new IllegalStateException("Cycle detected in desired state graph");
        }

        return layers;
    }

    private List<List<NodeId>> topologicalSortReverse(DesiredStateGraph graph, Set<NodeId> toSort) {
        List<List<NodeId>> layers = topologicalSort(graph, toSort);
        java.util.Collections.reverse(layers);
        return layers;
    }


    private Set<NodeId> nodesOfType(Set<NodeId> candidates, DesiredStateGraph graph, io.casehub.desiredstate.api.NodeType type) {
        Set<NodeId> result = new HashSet<>();
        for (NodeId nodeId : candidates) {
            DesiredNode node = graph.nodes().get(nodeId);
            if (node != null && node.type().equals(type)) {
                result.add(nodeId);
            }
        }
        return result;
    }

    private static class UnknownSpec implements NodeSpec {
        private static final NodeType UNKNOWN = NodeType.of("unknown");

        @Override
        public NodeType nodeType() {return UNKNOWN;}
    }
}
