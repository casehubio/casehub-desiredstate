package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeType;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GraphInvariantEngine {

    public void validate(DesiredStateGraph graph, List<ResolvedGraphInvariant> invariants) {
        List<GraphViolation> violations = new ArrayList<>();
        for (ResolvedGraphInvariant invariant : invariants) {
            if (invariant.imperative()) {
                validateImperative(invariant, graph, violations);
            } else {
                validateParameterized(invariant, graph, violations);
            }
        }
        if (!violations.isEmpty()) {
            throw new GraphInvariantViolationsException(violations);
        }
    }

    private void validateImperative(ResolvedGraphInvariant invariant,
            DesiredStateGraph graph, List<GraphViolation> violations) {
        try {
            if (invariant.instance() != null) {
                invariant.method().invoke(invariant.instance(), graph);
            } else {
                invariant.method().invoke(null, graph);
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof GraphViolationException gve) {
                violations.add(new GraphViolation(invariant.name(),
                        invariant.method().getDeclaringClass().getName(),
                        gve.getMessage(), gve.affectedNodes()));
            } else {
                throw new RuntimeException("Invariant method failed: "
                        + invariant.name(), e.getCause());
            }
        } catch (Exception e) {
            throw new RuntimeException("Invariant method invocation failed: "
                    + invariant.name(), e);
        }
    }

    private void validateParameterized(ResolvedGraphInvariant invariant,
                                       DesiredStateGraph graph, List<GraphViolation> violations) {
        List<PatternParameterDescriptor> patterns   = invariant.patterns();
        String[]                         paramNames = PatternMatchingSupport.getParameterNames(invariant.method());

        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).kind() == PatternKind.MATCH) {
                matchIndices.add(i);
            }
        }

        List<Map<String, DesiredNode>> allBindings = PatternEvaluator.evaluate(graph, patterns, paramNames);

        Map<List<DesiredNode>, List<Map<String, DesiredNode>>> byAnchor = new LinkedHashMap<>();
        for (Map<String, DesiredNode> binding : allBindings) {
            List<DesiredNode> anchor = matchIndices.stream()
                                                   .map(i -> binding.get(paramNames[i]))
                                                   .toList();
            byAnchor.computeIfAbsent(anchor, k -> new ArrayList<>()).add(binding);
        }

        NodeType firstMatchType = matchIndices.isEmpty() ? null
                                                         : NodeType.of(patterns.get(matchIndices.get(0)).nodeType());
        List<List<DesiredNode>> expectedAnchors = buildExpectedAnchors(graph, patterns, paramNames, matchIndices);

        for (List<DesiredNode> anchor : expectedAnchors) {
            List<Map<String, DesiredNode>> expansions = byAnchor.get(anchor);
            if (expansions == null || expansions.isEmpty()) {
                String anchorDesc = anchor.stream()
                                          .map(n -> n.id().value())
                                          .collect(Collectors.joining(", "));
                violations.add(new GraphViolation(invariant.name(),
                                                  invariant.method().getDeclaringClass().getName(),
                                                  invariant.name() + " violated for [" + anchorDesc + "]",
                                                  anchor.stream().map(DesiredNode::id).toList()));
            } else {
                for (Map<String, DesiredNode> binding : expansions) {
                    Object[] args = new Object[paramNames.length];
                    for (int i = 0; i < paramNames.length; i++) {
                        args[i] = binding.get(paramNames[i]);
                    }
                    invokeInvariant(invariant, List.of(args), violations);
                }
            }
        }
    }

    private List<List<DesiredNode>> buildExpectedAnchors(DesiredStateGraph graph,
                                                         List<PatternParameterDescriptor> patterns, String[] paramNames,
                                                         List<Integer> matchIndices) {
        List<List<DesiredNode>> matchSets = new ArrayList<>();
        for (int i : matchIndices) {
            NodeType targetType = NodeType.of(patterns.get(i).nodeType());
            matchSets.add(graph.nodes().values().stream()
                               .filter(n -> n.type().equals(targetType))
                               .toList());
        }
        if (matchSets.isEmpty() || matchSets.stream().anyMatch(List::isEmpty)) {
            return List.of();
        }
        return PatternMatchingSupport.crossProduct(matchSets);
    }

    private void invokeInvariant(ResolvedGraphInvariant invariant,
            List<Object> args, List<GraphViolation> violations) {
        try {
            if (invariant.instance() != null) {
                invariant.method().invoke(invariant.instance(), args.toArray());
            } else {
                invariant.method().invoke(null, args.toArray());
            }
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof GraphViolationException gve) {
                violations.add(new GraphViolation(invariant.name(),
                        invariant.method().getDeclaringClass().getName(),
                        gve.getMessage(), gve.affectedNodes()));
            } else {
                throw new RuntimeException("Invariant method failed: "
                        + invariant.name(), e.getCause());
            }
        } catch (Exception e) {
            throw new RuntimeException("Invariant method invocation failed: "
                    + invariant.name(), e);
        }
    }
}
