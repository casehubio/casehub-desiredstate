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
        List<PatternParameterDescriptor> patterns = invariant.patterns();
        String[] paramNames = PatternMatchingSupport.getParameterNames(invariant.method());

        List<Integer> matchIndices = new ArrayList<>();
        List<List<DesiredNode>> matchSets = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).kind() == PatternKind.MATCH) {
                matchIndices.add(i);
                NodeType targetType = NodeType.of(patterns.get(i).nodeType());
                matchSets.add(graph.nodes().values().stream()
                        .filter(n -> n.type().equals(targetType))
                        .toList());
            }
        }

        if (matchSets.isEmpty() || matchSets.stream().anyMatch(List::isEmpty)) {
            return;
        }

        List<List<DesiredNode>> anchorTuples = PatternMatchingSupport.crossProduct(matchSets);

        for (List<DesiredNode> anchorTuple : anchorTuples) {
            Map<String, DesiredNode> bindings = new LinkedHashMap<>();
            List<Object> args = new ArrayList<>();
            int matchIdx = 0;
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).kind() == PatternKind.MATCH) {
                    DesiredNode node = anchorTuple.get(matchIdx++);
                    bindings.put(paramNames[i], node);
                    args.add(node);
                } else {
                    args.add(null);
                }
            }

            List<List<Object>> expandedArgs = new ArrayList<>();
            int chainStart = matchIndices.isEmpty() ? 0
                    : matchIndices.get(matchIndices.size() - 1) + 1;
            expandChain(invariant, graph, patterns, paramNames, bindings,
                    args, chainStart, expandedArgs);

            if (expandedArgs.isEmpty()) {
                String anchorDesc = anchorTuple.stream()
                        .map(n -> n.id().value())
                        .collect(Collectors.joining(", "));
                violations.add(new GraphViolation(invariant.name(),
                        invariant.method().getDeclaringClass().getName(),
                        invariant.name() + " violated for [" + anchorDesc + "]",
                        anchorTuple.stream().map(DesiredNode::id).toList()));
            } else {
                for (List<Object> finalArgs : expandedArgs) {
                    invokeInvariant(invariant, finalArgs, violations);
                }
            }
        }
    }

    private void expandChain(ResolvedGraphInvariant invariant,
            DesiredStateGraph graph, List<PatternParameterDescriptor> patterns,
            String[] paramNames, Map<String, DesiredNode> bindings,
            List<Object> args, int startIndex, List<List<Object>> results) {
        int idx = startIndex;
        while (idx < patterns.size() && patterns.get(idx).kind() == PatternKind.MATCH) {
            idx++;
        }
        if (idx >= patterns.size()) {
            results.add(new ArrayList<>(args));
            return;
        }

        PatternParameterDescriptor p = patterns.get(idx);
        DesiredNode refNode;

        switch (p.kind()) {
            case DIRECT_DEP -> {
                refNode = PatternMatchingSupport.resolveReference(p, idx, paramNames, bindings);
                for (DesiredNode neighbor : PatternMatchingSupport.findDirectNeighbors(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    var newArgs = new ArrayList<>(args);
                    newBindings.put(paramNames[idx], neighbor);
                    newArgs.set(idx, neighbor);
                    expandChain(invariant, graph, patterns, paramNames,
                            newBindings, newArgs, idx + 1, results);
                }
            }
            case REACHES -> {
                refNode = PatternMatchingSupport.resolveReference(p, idx, paramNames, bindings);
                for (DesiredNode reached : PatternMatchingSupport.findReachable(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    var newArgs = new ArrayList<>(args);
                    newBindings.put(paramNames[idx], reached);
                    newArgs.set(idx, reached);
                    expandChain(invariant, graph, patterns, paramNames,
                            newBindings, newArgs, idx + 1, results);
                }
            }
            case NOT_EXISTS -> {
                boolean exists;
                if (p.of().isEmpty()) {
                    exists = PatternMatchingSupport.existsGlobal(graph, p);
                } else {
                    refNode = PatternMatchingSupport.resolveReference(p, idx, paramNames, bindings);
                    exists = PatternMatchingSupport.existsRelational(graph, refNode, p);
                }
                if (exists) return;
                var newArgs = new ArrayList<>(args);
                newArgs.set(idx, null);
                expandChain(invariant, graph, patterns, paramNames,
                        bindings, newArgs, idx + 1, results);
            }
            default -> throw new IllegalStateException("Unexpected pattern kind: " + p.kind());
        }
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
