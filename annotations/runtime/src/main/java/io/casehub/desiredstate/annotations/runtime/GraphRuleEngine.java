package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.CyclicDependencyException;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;

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
            graph = applyMutations(graph, sortByType(deduped), contributions);
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

    List<GraphMutation> evaluateParameterized(ResolvedGraphRule rule, DesiredStateGraph graph) {
        return List.of();
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

    private record RuleContribution(String ruleName, List<GraphMutation> mutations) {}
}
