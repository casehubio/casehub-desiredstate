package io.casehub.desiredstate.ts;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptorResolver;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantEngine;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleEngine;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.annotations.runtime.ResolvedRule;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.Phase;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Recorder
public class TsGraphRecorder {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createTsGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            List<ResolvedInvariant> invariants,
            List<GraphRuleDescriptor> crossSurfaceRuleDescriptors,
            List<GraphInvariantDescriptor> crossSurfaceInvariantDescriptors) {

        ObjectMapper mapper = new ObjectMapper();

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            List<DesiredNode> nodes = materializeNodes(descriptor.nodes(), typeRegistryMap, mapper);
            List<Dependency> deps = materializeDeps(descriptor.dependencies());

            DesiredStateGraph graph = factory.of(nodes, deps);

            if (crossSurfaceRuleDescriptors != null && !crossSurfaceRuleDescriptors.isEmpty()) {
                List<ResolvedRule> rules = GraphDescriptorResolver.resolveRules(crossSurfaceRuleDescriptors);
                graph = new GraphRuleEngine().evaluate(graph, rules);
            }

            List<ResolvedInvariant> effectiveInvariants = new ArrayList<>(invariants);
            if (crossSurfaceInvariantDescriptors != null && !crossSurfaceInvariantDescriptors.isEmpty()) {
                effectiveInvariants.addAll(GraphDescriptorResolver.resolveInvariants(crossSurfaceInvariantDescriptors));
            }
            if (!effectiveInvariants.isEmpty()) {
                new GraphInvariantEngine().validate(graph, effectiveInvariants);
            }

            return CompilationResult.single(graph);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createTsLifecycleGoalCompiler(
            TsLifecycleEnvelope envelope,
            Map<String, String> typeRegistryMap,
            List<ResolvedInvariant> invariants) {

        ObjectMapper mapper = new ObjectMapper();

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            List<DesiredNode> carryForwardNodes = new ArrayList<>();
            List<Dependency> carryForwardDeps = new ArrayList<>();
            List<Phase> phases = new ArrayList<>();

            for (TsEnvelopePhase phaseEnvelope : envelope.phases()) {
                List<DesiredNode> phaseNodes = materializePhaseNodes(
                        phaseEnvelope.nodes(), typeRegistryMap, mapper);
                List<Dependency> phaseDeps = materializeDeps(phaseEnvelope.dependencies());

                List<DesiredNode> mergedNodes = new ArrayList<>(phaseNodes);
                java.util.Set<NodeId> declaredIds = phaseNodes.stream()
                        .map(DesiredNode::id).collect(java.util.stream.Collectors.toSet());
                for (DesiredNode cf : carryForwardNodes) {
                    if (!declaredIds.contains(cf.id())) {
                        mergedNodes.add(cf);
                    }
                }

                java.util.Set<NodeId> mergedNodeIds = mergedNodes.stream()
                        .map(DesiredNode::id).collect(java.util.stream.Collectors.toSet());
                List<Dependency> mergedDeps = new ArrayList<>(phaseDeps);
                for (Dependency cf : carryForwardDeps) {
                    if (mergedNodeIds.contains(cf.from()) && mergedNodeIds.contains(cf.to())
                            && !declaredIds.contains(cf.from())) {
                        mergedDeps.add(cf);
                    }
                }

                DesiredStateGraph phaseGraph = factory.of(mergedNodes, mergedDeps);

                if (!invariants.isEmpty()) {
                    new GraphInvariantEngine().validate(phaseGraph, invariants);
                }

                CompletionCondition condition = resolveCompletionCondition(
                        phaseEnvelope.completionCondition());

                phases.add(new Phase(phaseEnvelope.id(), phaseGraph, condition));

                carryForwardNodes = new ArrayList<>(mergedNodes);
                carryForwardDeps = new ArrayList<>(mergedDeps);
            }

            return CompilationResult.lifecycle(phases);
        });
    }

    private static List<DesiredNode> materializeNodes(
            List<NodeDescriptor> descriptors,
            Map<String, String> typeRegistryMap,
            ObjectMapper mapper) {
        List<DesiredNode> nodes = new ArrayList<>();
        for (NodeDescriptor nd : descriptors) {
            if (nd instanceof NodeDescriptor.InlineNode in) {
                nodes.add(materializeInlineNode(in, typeRegistryMap, mapper));
            }
        }
        return nodes;
    }

    private static List<DesiredNode> materializePhaseNodes(
            List<TsEnvelopeNode> envelopeNodes,
            Map<String, String> typeRegistryMap,
            ObjectMapper mapper) {
        List<DesiredNode> nodes = new ArrayList<>();
        for (TsEnvelopeNode en : envelopeNodes) {
            String className = typeRegistryMap.get(en.type());
            if (className == null) {
                throw new IllegalStateException("Unknown node type: '" + en.type()
                        + "'. Available: " + typeRegistryMap.keySet());
            }
            nodes.add(materializeFromEnvelope(en, className, mapper));
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    private static DesiredNode materializeInlineNode(
            NodeDescriptor.InlineNode in,
            Map<String, String> typeRegistryMap,
            ObjectMapper mapper) {
        try {
            Class<? extends NodeSpec> specClass =
                    (Class<? extends NodeSpec>) Thread.currentThread()
                            .getContextClassLoader().loadClass(in.specClassName());
            NodeSpec spec = mapper.convertValue(in.specValues(), specClass);
            return new DesiredNode(NodeId.of(in.id()), spec, in.humanGating(), null);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("NodeSpec class not found: " + in.specClassName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static DesiredNode materializeFromEnvelope(
            TsEnvelopeNode en,
            String className,
            ObjectMapper mapper) {
        try {
            Class<? extends NodeSpec> specClass =
                    (Class<? extends NodeSpec>) Thread.currentThread()
                            .getContextClassLoader().loadClass(className);
            NodeSpec spec = mapper.convertValue(en.spec(), specClass);
            return new DesiredNode(NodeId.of(en.id()), spec, en.humanGating(), null);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("NodeSpec class not found: " + className, e);
        }
    }

    private static List<Dependency> materializeDeps(List<DependencyDescriptor> descriptors) {
        return descriptors.stream()
                .map(dd -> new Dependency(NodeId.of(dd.from()), NodeId.of(dd.to())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static CompletionCondition resolveCompletionCondition(Object condition) {
        if (condition instanceof String s) {
            return switch (s) {
                case "allPresent" -> CompletionCondition.allPresent();
                case "never" -> CompletionCondition.never();
                default -> throw new IllegalArgumentException(
                        "Unknown completion condition: '" + s + "'. Use 'allPresent', 'never', or {bean: 'name'}.");
            };
        }
        if (condition instanceof Map<?, ?> m) {
            String beanName = (String) m.get("bean");
            throw new UnsupportedOperationException(
                    "CDI bean completionCondition '" + beanName
                    + "' requires Quarkus runtime — use allPresent or never in unit tests");
        }
        throw new IllegalArgumentException("Invalid completion condition: " + condition);
    }
}
