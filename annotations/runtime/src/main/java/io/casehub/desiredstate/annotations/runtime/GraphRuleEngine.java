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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
        String[]                         paramNames   = PatternMatchingSupport.getParameterNames(rule.method());

        List<List<DesiredNode>> matchSets = new ArrayList<>();
        for (PatternParameterDescriptor p : patterns) {
            if (p.kind() == PatternKind.MATCH) {
                NodeType targetType = NodeType.of(p.nodeType());
                List<DesiredNode> matches = graph.nodes().values().stream()
                                                 .filter(n -> n.type().equals(targetType)).toList();
                matchSets.add(matches);
            }
        }

        for (List<DesiredNode> matchTuple : PatternMatchingSupport.crossProduct(matchSets)) {
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
        DesiredNode refNode = PatternMatchingSupport.resolveReference(p, idx, paramNames, bindings);

        switch (p.kind()) {
            case DIRECT_DEP -> {
                for (DesiredNode neighbor : PatternMatchingSupport.findDirectNeighbors(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    var newArgs = new ArrayList<>(args);
                    newBindings.put(paramNames[idx], neighbor);
                    newArgs.set(idx, neighbor);
                    expandChain(rule, graph, patterns, paramNames, newBindings, newArgs,
                            idx + 1, allMutations);
                }
            }
            case REACHES -> {
                for (DesiredNode reached : PatternMatchingSupport.findReachable(graph, refNode, p)) {
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
                        ? PatternMatchingSupport.existsGlobal(graph, p)
                        : PatternMatchingSupport.existsRelational(graph, bindings.get(p.of()), p);
                if (exists) return;
                var newArgs = new ArrayList<>(args);
                newArgs.set(idx, null);
                expandChain(rule, graph, patterns, paramNames, bindings, newArgs,
                        idx + 1, allMutations);
            }
            default -> throw new IllegalStateException("Unexpected pattern kind: " + p.kind());
        }
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

    private record RuleContribution(String ruleName, List<GraphMutation> mutations) {}
}
