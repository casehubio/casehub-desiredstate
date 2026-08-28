package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.ConflictingMutationException;
import io.casehub.desiredstate.api.CyclicDependencyException;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
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

    public DesiredStateGraph evaluate(DesiredStateGraph graph, List<ResolvedRule> rules) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            List<RuleContribution> contributions = new ArrayList<>();

            for (ResolvedRule rule : rules) {
                List<GraphMutation> mutations = evaluateRule(rule, graph);
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
        List<ResolvedRule> activeRules = rules.stream()
                                              .filter(r -> !evaluateRule(r, finalGraph).isEmpty())
                                              .toList();
        throw new GraphRuleNonConvergenceException(
                activeRules.isEmpty() ? rules : activeRules, MAX_ITERATIONS);
    }

    private List<GraphMutation> evaluateRule(ResolvedRule rule, DesiredStateGraph graph) {
        return switch (rule) {
            case ResolvedRule.ImperativeRule imp -> evaluateImperative(imp, graph);
            case ResolvedRule.ParameterizedReflectiveRule param -> evaluateParameterized(param, graph);
            case ResolvedRule.DeclarativeRule decl -> evaluateDeclarative(decl, graph);
        };}

    @SuppressWarnings("unchecked")
    private List<GraphMutation> evaluateImperative(ResolvedRule.ImperativeRule rule, DesiredStateGraph graph) {
        try {
            return (List<GraphMutation>) rule.method().invoke(rule.instance(), graph);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {throw re;}
            throw new RuntimeException("Rule " + rule.name() + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Rule " + rule.name() + " inaccessible", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<GraphMutation> evaluateParameterized(ResolvedRule.ParameterizedReflectiveRule rule,
                                                      DesiredStateGraph graph) {
        List<GraphMutation>              allMutations = new ArrayList<>();
        List<PatternParameterDescriptor> patterns     = rule.patterns();
        String[]                         paramNames   = rule.bindingNames();

        List<Map<String, DesiredNode>> allBindings = PatternEvaluator.evaluate(graph, patterns, paramNames);

        for (Map<String, DesiredNode> binding : allBindings) {
            List<Object> args = new ArrayList<>(paramNames.length);
            for (String paramName : paramNames) {
                args.add(binding.get(paramName));
            }
            invokeRule(rule, args, allMutations);
        }

        return allMutations;
    }

    private List<GraphMutation> evaluateDeclarative(ResolvedRule.DeclarativeRule rule,
                                                    DesiredStateGraph graph) {
        List<GraphMutation> allMutations = new ArrayList<>();
        List<Map<String, io.casehub.desiredstate.api.DesiredNode>> allBindings =
                PatternEvaluator.evaluate(graph, rule.patterns(), rule.bindingNames());
        for (Map<String, io.casehub.desiredstate.api.DesiredNode> binding : allBindings) {
            List<GraphMutation> mutations = rule.actionEvaluator().apply(binding);
            if (mutations != null && !mutations.isEmpty()) {
                allMutations.addAll(mutations);
            }
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
            if (target == null) {continue;}
            GraphMutation existing = byNodeId.get(target);
            if (existing != null && !existing.equals(m)) {
                throw new ConflictingMutationException(target, existing, m);
            }
            byNodeId.put(target, m);
        }
    }

    private void detectEdgeConflicts(List<GraphMutation> mutations) {
        Map<Dependency, GraphMutation> addEdges    = new HashMap<>();
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

    @SuppressWarnings("unchecked")
    private void invokeRule(ResolvedRule.ParameterizedReflectiveRule rule, List<Object> args,
                            List<GraphMutation> allMutations) {
        try {
            var result = (List<GraphMutation>) rule.method().invoke(rule.instance(), args.toArray());
            if (result != null && !result.isEmpty()) {
                allMutations.addAll(result);
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {throw re;}
            throw new RuntimeException("Rule " + rule.name() + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Rule " + rule.name() + " inaccessible", e);
        }
    }

    private record RuleContribution(String ruleName, List<GraphMutation> mutations) {}
}
