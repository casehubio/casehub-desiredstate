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

    public void validate(DesiredStateGraph graph, List<ResolvedInvariant> invariants) {
        List<GraphViolation> violations = new ArrayList<>();
        for (ResolvedInvariant invariant : invariants) {
            switch (invariant) {
                case ResolvedInvariant.ImperativeInvariant imp -> validateImperative(imp, graph, violations);
                case ResolvedInvariant.ParameterizedReflectiveInvariant param -> validateParameterized(param, graph, violations);
                case ResolvedInvariant.DeclarativeInvariant decl -> validateDeclarative(decl, graph, violations);
            }
        }
        if (!violations.isEmpty()) {
            throw new GraphInvariantViolationsException(violations);
        }
    }

    private void validateImperative(ResolvedInvariant.ImperativeInvariant invariant,
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

    private void validateParameterized(ResolvedInvariant.ParameterizedReflectiveInvariant invariant,
                                       DesiredStateGraph graph, List<GraphViolation> violations) {
        List<PatternParameterDescriptor> patterns   = invariant.patterns();
        String[]                         paramNames = invariant.bindingNames();

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

        List<List<DesiredNode>> expectedAnchors = buildExpectedAnchors(graph, patterns, matchIndices);

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
                    List<Object> args = new ArrayList<>(paramNames.length);
                    for (String paramName : paramNames) {
                        args.add(binding.get(paramName));
                    }
                    invokeReflectiveInvariant(invariant, args, violations);
                }
            }
        }
    }

    private void validateDeclarative(ResolvedInvariant.DeclarativeInvariant invariant,
                                     DesiredStateGraph graph, List<GraphViolation> violations) {
        List<PatternParameterDescriptor> patterns     = invariant.patterns();
        String[]                         bindingNames = invariant.bindingNames();

        List<Integer> matchIndices = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).kind() == PatternKind.MATCH) {
                matchIndices.add(i);
            }
        }

        List<Map<String, DesiredNode>> allBindings = PatternEvaluator.evaluate(graph, patterns, bindingNames);

        Map<List<DesiredNode>, List<Map<String, DesiredNode>>> byAnchor = new LinkedHashMap<>();
        for (Map<String, DesiredNode> binding : allBindings) {
            List<DesiredNode> anchor = matchIndices.stream()
                                                   .map(i -> binding.get(bindingNames[i]))
                                                   .toList();
            byAnchor.computeIfAbsent(anchor, k -> new ArrayList<>()).add(binding);
        }

        List<List<DesiredNode>> expectedAnchors = buildExpectedAnchors(graph, patterns, matchIndices);

        for (List<DesiredNode> anchor : expectedAnchors) {
            List<Map<String, DesiredNode>> expansions = byAnchor.get(anchor);
            if (expansions == null || expansions.isEmpty()) {
                String anchorDesc = anchor.stream()
                                          .map(n -> n.id().value())
                                          .collect(Collectors.joining(", "));
                String message = invariant.messageTemplate() != null
                                 ? resolveMatchTemplate(invariant.messageTemplate(), anchor, matchIndices, bindingNames)
                                 : invariant.name() + " violated for [" + anchorDesc + "]";
                violations.add(new GraphViolation(invariant.name(), "yaml",
                                                  message, anchor.stream().map(DesiredNode::id).toList()));
            }
        }
    }

    private String resolveMatchTemplate(String template, List<DesiredNode> anchor,
                                        List<Integer> matchIndices, String[] bindingNames) {
        String resolved = template;
        for (int i = 0; i < matchIndices.size(); i++) {
            DesiredNode node    = anchor.get(i);
            String      binding = bindingNames[matchIndices.get(i)];
            resolved = resolved.replace("${match." + binding + ".id}", node.id().value());
            resolved = resolved.replace("${match." + binding + ".type}", node.type().value());
        }
        return resolved;
    }

    private List<List<DesiredNode>> buildExpectedAnchors(DesiredStateGraph graph,
                                                         List<PatternParameterDescriptor> patterns, List<Integer> matchIndices) {
        List<List<DesiredNode>> matchSets = new ArrayList<>();
        for (int i : matchIndices) {
            PatternParameterDescriptor p = patterns.get(i);
            if ("*".equals(p.nodeType())) {
                matchSets.add(new ArrayList<>(graph.nodes().values()));
            } else {
                NodeType targetType = NodeType.of(p.nodeType());
                matchSets.add(graph.nodes().values().stream()
                                   .filter(n -> n.type().equals(targetType))
                                   .toList());
            }
        }
        if (matchSets.isEmpty() || matchSets.stream().anyMatch(List::isEmpty)) {
            return List.of();
        }
        return PatternMatchingSupport.crossProduct(matchSets);
    }

    private void invokeReflectiveInvariant(ResolvedInvariant.ParameterizedReflectiveInvariant invariant,
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
