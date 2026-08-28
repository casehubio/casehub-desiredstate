package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PatternEvaluator {

    private PatternEvaluator() {}

    public static List<Map<String, DesiredNode>> evaluate(
            DesiredStateGraph graph,
            List<PatternParameterDescriptor> patterns,
            String[] bindingNames) {

        List<List<DesiredNode>> matchSets = new ArrayList<>();
        for (PatternParameterDescriptor p : patterns) {
            if (p.kind() == PatternKind.MATCH) {
                if ("*".equals(p.nodeType())) {
                    matchSets.add(new ArrayList<>(graph.nodes().values()));
                } else {
                    NodeType targetType = NodeType.of(p.nodeType());
                    matchSets.add(graph.nodes().values().stream()
                            .filter(n -> n.type().equals(targetType))
                            .toList());
                }
            }
        }

        List<Map<String, DesiredNode>> results = new ArrayList<>();
        for (List<DesiredNode> tuple : PatternMatchingSupport.crossProduct(matchSets)) {
            Map<String, DesiredNode> bindings = new LinkedHashMap<>();
            int matchIdx = 0;
            for (int i = 0; i < patterns.size(); i++) {
                if (patterns.get(i).kind() == PatternKind.MATCH) {
                    bindings.put(bindingNames[i], tuple.get(matchIdx++));
                }
            }
            expandChain(graph, patterns, bindingNames, bindings, 0, results);
        }
        return results;
    }

    private static void expandChain(DesiredStateGraph graph,
            List<PatternParameterDescriptor> patterns, String[] bindingNames,
            Map<String, DesiredNode> bindings, int startIndex,
            List<Map<String, DesiredNode>> results) {
        int idx = startIndex;
        while (idx < patterns.size() && patterns.get(idx).kind() == PatternKind.MATCH) {
            idx++;
        }
        if (idx >= patterns.size()) {
            results.add(new LinkedHashMap<>(bindings));
            return;
        }

        PatternParameterDescriptor p = patterns.get(idx);

        switch (p.kind()) {
            case DIRECT_DEP -> {
                DesiredNode refNode = PatternMatchingSupport.resolveReference(p, idx, bindingNames, bindings);
                for (DesiredNode neighbor : PatternMatchingSupport.findDirectNeighbors(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    newBindings.put(bindingNames[idx], neighbor);
                    expandChain(graph, patterns, bindingNames, newBindings, idx + 1, results);
                }
            }
            case REACHES -> {
                DesiredNode refNode = PatternMatchingSupport.resolveReference(p, idx, bindingNames, bindings);
                for (DesiredNode reached : PatternMatchingSupport.findReachable(graph, refNode, p)) {
                    var newBindings = new LinkedHashMap<>(bindings);
                    newBindings.put(bindingNames[idx], reached);
                    expandChain(graph, patterns, bindingNames, newBindings, idx + 1, results);
                }
            }
            case NOT_EXISTS -> {
                boolean exists = p.of().isEmpty()
                        ? PatternMatchingSupport.existsGlobal(graph, p)
                        : PatternMatchingSupport.existsRelational(graph, bindings.get(p.of()), p);
                if (exists) return;
                expandChain(graph, patterns, bindingNames, bindings, idx + 1, results);
            }
            default -> throw new IllegalStateException("Unexpected pattern kind: " + p.kind());
        }
    }
}
