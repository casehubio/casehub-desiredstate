package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.CyclicDependencyException;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GraphRuleEngine {

    private static final int MAX_ITERATIONS = 100;

    public DesiredStateGraph evaluate(DesiredStateGraph graph, List<ResolvedGraphRule> rules) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            List<RuleContribution> contributions = new ArrayList<>();

            for (ResolvedGraphRule rule : rules) {
                List<GraphMutation> mutations = rule.imperative()
                                                ? evaluateImperative(rule, graph)
                                                : evaluateParameterized(rule, graph);
                if (!mutations.isEmpty()) {
                    contributions.add(new RuleContribution(rule.name(), mutations));
                }
            }

            List<GraphMutation> allMutations = contributions.stream()
                                                            .flatMap(c -> c.mutations().stream()).toList();

            if (allMutations.isEmpty()) {
                return graph;
            }

            List<GraphMutation> deduped = deduplicateMutations(allMutations);
            detectNodeConflicts(deduped);
            detectEdgeConflicts(deduped);
            List<GraphMutation> effective = filterNoOps(deduped, graph);
            if (effective.isEmpty()) {
                return graph;
            }
            graph = applyMutations(graph, sortByType(effective), contributions);
        }

        DesiredStateGraph finalGraph = graph;
        List<ResolvedGraphRule> activeRules = rules.stream()
                                                   .filter(r -> !(r.imperative()
                                                                  ? evaluateImperative(r, finalGraph)
                                                                  : evaluateParameterized(r, finalGraph)).isEmpty())
                                                   .toList();
        throw new GraphRuleNonConvergenceException(
                activeRules.isEmpty() ? rules : activeRules, MAX_ITERATIONS);}

    @SuppressWarnings("unchecked")
    List<GraphMutation> evaluateImperative(ResolvedGraphRule rule, DesiredStateGraph graph) {
        try {
            return (List<GraphMutation>) rule.method().invoke(rule.instance(), graph);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Rule " + rule.name() + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Rule " + rule.name() + " inaccessible", e);
        }
    }

    @SuppressWarnings("unchecked")
    List<GraphMutation> evaluateParameterized(ResolvedGraphRule rule, DesiredStateGraph graph) {
        List<GraphMutation>              allMutations = new ArrayList<>();
        List<PatternParameterDescriptor> patterns     = rule.patterns();
        String[]                         paramNames   = getParameterNames(rule.method());

        List<List<DesiredNode>> matchSets = new ArrayList<>();
        for (PatternParameterDescriptor p : patterns) {
            if (p.kind() == PatternKind.MATCH) {
                NodeType targetType = NodeType.of(p.nodeType());
                List<DesiredNode> matches = graph.nodes().values().stream()
                                                 .filter(n -> n.type().equals(targetType)).toList();
                matchSets.add(matches);
            }
        }

        for (List<DesiredNode> matchTuple : crossProduct(matchSets)) {
            expandBindings(rule, graph, patterns, paramNames, matchTuple, allMutations);
        }

        return allMutations;
    }

    private List<GraphMutation> deduplicateMutations(List<GraphMutation> mutations) {
        return new ArrayList<>(new LinkedHashSet<>(mutations));
    }

    private void detectNodeConflicts(List<GraphMutation> mutations) {
        Map<NodeId, GraphMutation> byNodeId = new LinkedHashMap<>();
        for (GraphMutation m : mutations) {
            NodeId target = m.targetNodeId();
            if (target == null) continue;
            GraphMutation existing = byNodeId.get(target);
            if (existing != null && !existing.equals(m)) {
                throw new ConflictingMutationException(target, existing, m);
            }
            byNodeId.put(target, m);
        }
    }

    private void detectEdgeConflicts(List<GraphMutation> mutations) {
        Map<Dependency, GraphMutation> addEdges = new HashMap<>();
        Map<Dependency, GraphMutation> removeEdges = new HashMap<>();
        for (GraphMutation m : mutations) {
            switch (m) {
                case GraphMutation.AddDependency add -> addEdges.put(add.dependency(), m);
                case GraphMutation.RemoveDependency rem -> removeEdges.put(rem.dependency(), m);
                default -> {}
            }
        }
        for (var entry : addEdges.entrySet()) {
            GraphMutation remove = removeEdges.get(entry.getKey());
            if (remove != null) {
                throw new ConflictingMutationException(
                        entry.getKey().from(), entry.getValue(), remove);
            }
        }
    }

    private List<GraphMutation> filterNoOps(List<GraphMutation> mutations, DesiredStateGraph graph) {
        return mutations.stream().filter(m -> switch (m) {
            case GraphMutation.AddNode add -> {
                DesiredNode existing = graph.nodes().get(add.node().id());
                yield existing == null || !existing.equals(add.node());
            }
            case GraphMutation.RemoveNode rem -> graph.nodes().containsKey(rem.id());
            case GraphMutation.UpdateNode upd -> {
                DesiredNode existing = graph.nodes().get(upd.id());
                yield existing == null || !existing.equals(upd.adaptedNode());
            }
            case GraphMutation.AddDependency add -> !graph.dependencies().contains(add.dependency());
            case GraphMutation.RemoveDependency rem -> graph.dependencies().contains(rem.dependency());
        }).toList();
    }


    private DesiredStateGraph applyMutations(DesiredStateGraph graph, List<GraphMutation> sorted,
                                              List<RuleContribution> contributions) {
        try {
            for (GraphMutation m : sorted) {
                graph = graph.withMutation(m);
            }
            return graph;
        } catch (CyclicDependencyException e) {
            List<String> ruleNames = contributions.stream()
                    .map(RuleContribution::ruleName).toList();
            throw new GraphRuleCycleException(ruleNames, e.getCycle());
        }
    }

    private List<GraphMutation> sortByType(List<GraphMutation> mutations) {
        return mutations.stream().sorted(Comparator.comparingInt(m -> switch (m) {
            case GraphMutation.AddNode ignored -> 0;
            case GraphMutation.UpdateNode ignored -> 1;
            case GraphMutation.RemoveDependency ignored -> 2;
            case GraphMutation.RemoveNode ignored -> 3;
            case GraphMutation.AddDependency ignored -> 4;
        })).toList();
    }

    private void expandBindings(ResolvedGraphRule rule, DesiredStateGraph graph,
            List<PatternParameterDescriptor> patterns, String[] paramNames,
            List<DesiredNode> matchTuple, List<GraphMutation> allMutations) {
        Map<String, DesiredNode> bindings = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        int matchIdx = 0;

        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).kind() == PatternKind.MATCH) {
                DesiredNode node = matchTuple.get(matchIdx++);
                bindings.put(paramNames[i], node);
                args.add(node);
            } else {
                args.add(null);
            }
        }

        expandChain(rule, graph, patterns, paramNames, bindings, args, 0, allMutations);
    }

    private void expandChain(ResolvedGraphRule rule, DesiredStateGraph graph,
            List<PatternParameterDescriptor> patterns, String[] paramNames,
            Map<String, DesiredNode> bindings, List<Object> args,
            int startIndex, List<GraphMutation> allMutations) {
        int idx = startIndex;
        while (idx < patterns.size() && patterns.get(idx).kind() == PatternKind.MATCH) {
            idx++;
        }
        if (idx >= patterns.size()) {
            invokeRule(rule, args, allMutations);
            return;
        }

        PatternParameterDescriptor p = patterns.get(idx);
        DesiredNode refNode = resolveReference(p, idx, paramNames, bindings);

        switch (p.kind()) {
            case DIRECT_DEP -> {
                for (DesiredNode neighbor : findDirectNeighbors(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    var newArgs = new ArrayList<>(args);
                    newBindings.put(paramNames[idx], neighbor);
                    newArgs.set(idx, neighbor);
                    expandChain(rule, graph, patterns, paramNames, newBindings, newArgs,
                            idx + 1, allMutations);
                }
            }
            case REACHES -> {
                for (DesiredNode reached : findReachable(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    var newArgs = new ArrayList<>(args);
                    newBindings.put(paramNames[idx], reached);
                    newArgs.set(idx, reached);
                    expandChain(rule, graph, patterns, paramNames, newBindings, newArgs,
                            idx + 1, allMutations);
                }
            }
            case NOT_EXISTS -> {
                boolean exists = p.of().isEmpty()
                        ? existsGlobal(graph, p)
                        : existsRelational(graph, bindings.get(p.of()), p);
                if (exists) return;
                var newArgs = new ArrayList<>(args);
                newArgs.set(idx, null);
                expandChain(rule, graph, patterns, paramNames, bindings, newArgs,
                        idx + 1, allMutations);
            }
            default -> throw new IllegalStateException("Unexpected pattern kind: " + p.kind());
        }
    }

    private DesiredNode resolveReference(PatternParameterDescriptor p, int paramIndex,
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

    private List<DesiredNode> findDirectNeighbors(DesiredStateGraph graph,
            DesiredNode refNode, PatternParameterDescriptor p) {
        NodeType targetType = NodeType.of(p.nodeType());
        Set<NodeId> neighbors = p.direction() == Direction.DEPENDENCIES
                ? graph.dependenciesOf(refNode.id())
                : graph.dependentsOf(refNode.id());
        return neighbors.stream()
                .map(id -> graph.nodes().get(id))
                .filter(n -> n != null && n.type().equals(targetType))
                .toList();
    }

    private List<DesiredNode> findReachable(DesiredStateGraph graph,
            DesiredNode refNode, PatternParameterDescriptor p) {
        NodeType targetType = NodeType.of(p.nodeType());
        List<DesiredNode> found = new ArrayList<>();
        Set<NodeId> visited = new HashSet<>();
        ArrayDeque<NodeId> queue = new ArrayDeque<>();
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
                    if (node != null && node.type().equals(targetType)) {
                        found.add(node);
                    }
                    queue.add(neighbor);
                }
            }
        }
        return found;
    }

    private boolean existsGlobal(DesiredStateGraph graph, PatternParameterDescriptor p) {
        NodeType targetType = NodeType.of(p.nodeType());
        return graph.nodes().values().stream().anyMatch(n -> n.type().equals(targetType));
    }

    private boolean existsRelational(DesiredStateGraph graph, DesiredNode refNode,
            PatternParameterDescriptor p) {
        NodeType targetType = NodeType.of(p.nodeType());
        Set<NodeId> neighbors = p.direction() == Direction.DEPENDENCIES
                ? graph.dependenciesOf(refNode.id())
                : graph.dependentsOf(refNode.id());
        return neighbors.stream()
                .map(id -> graph.nodes().get(id))
                .anyMatch(n -> n != null && n.type().equals(targetType));
    }

    @SuppressWarnings("unchecked")
    private void invokeRule(ResolvedGraphRule rule, List<Object> args,
            List<GraphMutation> allMutations) {
        try {
            var result = (List<GraphMutation>) rule.method().invoke(rule.instance(), args.toArray());
            if (result != null && !result.isEmpty()) {
                allMutations.addAll(result);
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Rule " + rule.name() + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Rule " + rule.name() + " inaccessible", e);
        }
    }

    private String[] getParameterNames(Method method) {
        var params = method.getParameters();
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            names[i] = params[i].getName();
        }
        return names;
    }

    private List<List<DesiredNode>> crossProduct(List<List<DesiredNode>> sets) {
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

    private record RuleContribution(String ruleName, List<GraphMutation> mutations) {}
}
