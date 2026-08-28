package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantEngine;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import io.casehub.desiredstate.yaml.resolver.VariableResolver;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Recorder
public class YamlGraphRecorder {

    private static final Logger LOG = Logger.getLogger(YamlGraphRecorder.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants) {
        return createYamlGoalCompiler(descriptor, typeRegistryMap, inlineVariables, invariants, null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants,
            io.casehub.desiredstate.yaml.model.YamlGraph yamlGraph) {
        return createYamlGoalCompiler(descriptor, typeRegistryMap, inlineVariables, invariants, yamlGraph, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants,
            io.casehub.desiredstate.yaml.model.YamlGraph yamlGraph,
            Map<String, io.casehub.desiredstate.yaml.model.YamlModule> availableModules) {

        ObjectMapper     mapper   = new ObjectMapper();
        NodeSpecRegistry registry = NodeSpecRegistry.of(typeRegistryMap);

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            VariableResolver resolver = new VariableResolver(inlineVariables, null, null);

            Map<String, io.casehub.desiredstate.yaml.model.YamlNode> effectiveNodes =
                    yamlGraph != null ? new java.util.LinkedHashMap<>(yamlGraph.nodes()) : Map.of();
            Map<String, Map<String, String>> moduleScopes = Map.of();
            Map<String, io.casehub.desiredstate.yaml.model.YamlRule> promotedRules = Map.of();
            Map<String, io.casehub.desiredstate.yaml.model.YamlInvariant> promotedInvariants = Map.of();

            if (yamlGraph != null && !yamlGraph.imports().isEmpty() && availableModules != null) {
                ModuleExpander.ExpandedGraph moduleExpanded = ModuleExpander.expand(
                        yamlGraph.imports(), availableModules, effectiveNodes);
                effectiveNodes = moduleExpanded.expandedNodes();
                moduleScopes = moduleExpanded.moduleScopes();
                promotedRules = moduleExpanded.promotedRules();
                promotedInvariants = moduleExpanded.promotedInvariants();
            }

            boolean hasForEach = effectiveNodes.values().stream()
                    .anyMatch(n -> n.forEach() != null);
            boolean hasModules = !moduleScopes.isEmpty();

            DesiredStateGraph graph;
            if (hasForEach || hasModules) {
                ForEachExpander.ExpandedNodes expanded = ForEachExpander.expand(
                        effectiveNodes,
                        yamlGraph != null && yamlGraph.iterations() != null ? yamlGraph.iterations() : Map.of(),
                        resolver, registry, mapper, 1000, moduleScopes);
                graph = factory.of(expanded.nodes(), expanded.dependencies());
            } else {
                Set<String> excludedNodeIds = new java.util.HashSet<>();
                if (yamlGraph != null) {
                    for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlNode> entry :
                            yamlGraph.nodes().entrySet()) {
                        String                                      nodeId   = entry.getKey();
                        io.casehub.desiredstate.yaml.model.YamlNode yamlNode = entry.getValue();
                        if (yamlNode.when() != null) {
                            String resolved = resolver.resolveString(yamlNode.when(), nodeId);
                            if (!isTruthy(resolved)) {
                                excludedNodeIds.add(nodeId);
                            }
                        }
                    }
                }

                List<DesiredNode> nodes = new ArrayList<>();
                for (NodeDescriptor nd : descriptor.nodes()) {
                    if (nd instanceof NodeDescriptor.InlineNode in) {
                        if (excludedNodeIds.contains(in.id())) {continue;}

                        Class<? extends NodeSpec> specClass = registry.resolveByClassName(in.specClassName());
                        Map<String, Object>       resolved  = resolver.resolveMap(in.specValues(), in.id());
                        NodeSpec                  spec      = mapper.convertValue(resolved, specClass);

                        String expectedType = findTypeNameForClass(typeRegistryMap, in.specClassName());
                        if (expectedType != null && !spec.nodeType().value().equals(expectedType)) {
                            throw new IllegalStateException(
                                    "@NodeTypeId(\"" + expectedType + "\") diverges from nodeType()=\""
                                    + spec.nodeType().value() + "\" on " + specClass.getName());
                        }

                        nodes.add(new DesiredNode(NodeId.of(in.id()), spec, in.humanGating()));
                    }
                }

                List<Dependency> deps = new ArrayList<>();
                for (DependencyDescriptor dd : descriptor.dependencies()) {
                    if (excludedNodeIds.contains(dd.from()) || excludedNodeIds.contains(dd.to())) {
                        if (excludedNodeIds.contains(dd.to()) && !excludedNodeIds.contains(dd.from())) {
                            boolean isOptional = yamlGraph != null && isOptionalDependency(yamlGraph, dd.from(), dd.to());
                            if (!isOptional) {
                                throw new IllegalStateException("Node '" + dd.from()
                                                                + "' depends on excluded conditional node '" + dd.to() + "'");
                            }
                        }
                        continue;
                    }
                    deps.add(new Dependency(NodeId.of(dd.from()), NodeId.of(dd.to())));
                }

                graph = factory.of(nodes, deps);
            }

            Map<String, io.casehub.desiredstate.yaml.model.YamlRule> effectiveRules = new java.util.LinkedHashMap<>();
            if (yamlGraph != null) {effectiveRules.putAll(yamlGraph.rules());}
            effectiveRules.putAll(promotedRules);

            if (!effectiveRules.isEmpty()) {
                List<io.casehub.desiredstate.annotations.runtime.ResolvedRule> resolvedRules =
                        new ArrayList<>();
                for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlRule> ruleEntry :
                        effectiveRules.entrySet()) {
                    resolvedRules.add(YamlRuleConverter.toDeclarativeRule(
                            ruleEntry.getKey(), ruleEntry.getValue(), resolver, registry));
                }
                graph = new io.casehub.desiredstate.annotations.runtime.GraphRuleEngine()
                        .evaluate(graph, resolvedRules);
            }

            List<ResolvedInvariant> effectiveInvariants = new ArrayList<>(invariants);
            for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlInvariant> invEntry :
                    promotedInvariants.entrySet()) {
                effectiveInvariants.add(YamlInvariantConverter.toDeclarativeInvariant(
                        invEntry.getKey(), invEntry.getValue()));
            }

            if (!effectiveInvariants.isEmpty()) {
                new GraphInvariantEngine().validate(graph, effectiveInvariants);
            }

            return CompilationResult.single(graph);
        });
    }

    private static boolean isOptionalDependency(io.casehub.desiredstate.yaml.model.YamlGraph yamlGraph,
                                                String fromNodeId, String toNodeId) {
        io.casehub.desiredstate.yaml.model.YamlNode node = yamlGraph.nodes().get(fromNodeId);
        if (node == null) {return false;}
        for (Object dep : node.dependsOn()) {
            String depId = io.casehub.desiredstate.yaml.model.YamlNode.dependencyNodeId(dep);
            if (depId.equals(toNodeId)) {
                return io.casehub.desiredstate.yaml.model.YamlNode.isDependencyOptional(dep);
            }
        }
        return false;
    }

    static boolean isTruthy(String value) {
        return switch (value.toLowerCase()) {
            case "true", "yes", "on", "y", "1" -> true;
            case "false", "no", "off", "n", "0" -> false;
            default -> throw new IllegalArgumentException(
                    "when: condition resolved to '" + value
                    + "' which is not a boolean value. "
                    + "Expected: true/false/yes/no/on/off/y/n/1/0");
        };
    }

    @SuppressWarnings("rawtypes")
    public RuntimeValue<io.casehub.desiredstate.api.ThresholdFaultPolicy> createYamlFaultPolicy(
            io.casehub.desiredstate.yaml.model.YamlFaultPolicy yamlPolicy,
            Map<String, String> typeRegistryMap) {
        return new RuntimeValue<>(YamlFaultPolicyBuilder.build(
                yamlPolicy, typeRegistryMap,
                new io.casehub.desiredstate.api.InMemoryFaultCountStore()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlLifecycleGoalCompiler(
            io.casehub.desiredstate.yaml.model.YamlGraph yamlGraph,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants) {

        ObjectMapper     mapper   = new ObjectMapper();
        NodeSpecRegistry registry = NodeSpecRegistry.of(typeRegistryMap);

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            VariableResolver                             resolver          = new VariableResolver(inlineVariables, null, null);
            List<io.casehub.desiredstate.api.Phase>      phases            = new ArrayList<>();
            List<DesiredNode>                            carryForwardNodes = new ArrayList<>();
            List<io.casehub.desiredstate.api.Dependency> carryForwardDeps  = new ArrayList<>();

            for (io.casehub.desiredstate.yaml.model.YamlPhase yamlPhase :
                    yamlGraph.lifecycle().phases()) {

                boolean phaseHasForEach = yamlPhase.nodes().values().stream()
                        .anyMatch(n -> n.forEach() != null);

                List<DesiredNode> phaseNodes;
                List<io.casehub.desiredstate.api.Dependency> phaseDeps;
                Set<String> phaseNodeIds = new HashSet<>();

                if (phaseHasForEach) {
                    ForEachExpander.ExpandedNodes expanded = ForEachExpander.expand(
                            yamlPhase.nodes(),
                            yamlGraph.iterations() != null ? yamlGraph.iterations() : Map.of(),
                            resolver, registry, mapper, 1000);
                    phaseNodes = expanded.nodes();
                    phaseDeps = new ArrayList<>(expanded.dependencies());
                    phaseNodes.forEach(n -> phaseNodeIds.add(n.id().value()));
                } else {
                    Set<String> excludedNodeIds = new HashSet<>();
                    phaseNodes = new ArrayList<>();
                    phaseDeps = new ArrayList<>();

                    for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlNode> entry :
                            yamlPhase.nodes().entrySet()) {
                        String                                      nodeId   = entry.getKey();
                        io.casehub.desiredstate.yaml.model.YamlNode yamlNode = entry.getValue();

                        if (yamlNode.when() != null) {
                            String resolved = resolver.resolveString(yamlNode.when(), nodeId);
                            if (!isTruthy(resolved)) {
                                excludedNodeIds.add(nodeId);
                                continue;
                            }
                        }

                        Class<? extends NodeSpec> specClass = registry.resolve(yamlNode.type());
                        Map<String, Object> resolvedSpec = resolver.resolveMap(
                                yamlNode.spec() != null ? yamlNode.spec() : Map.of(), nodeId);
                        NodeSpec spec = mapper.convertValue(resolvedSpec, specClass);
                        phaseNodes.add(new DesiredNode(NodeId.of(nodeId), spec, yamlNode.humanGating()));
                        phaseNodeIds.add(nodeId);
                    }

                    for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlNode> entry :
                            yamlPhase.nodes().entrySet()) {
                        String nodeId = entry.getKey();
                        if (excludedNodeIds.contains(nodeId)) {continue;}
                        for (Object dep : entry.getValue().dependsOn()) {
                            String depId = io.casehub.desiredstate.yaml.model.YamlNode.dependencyNodeId(dep);
                            if (excludedNodeIds.contains(depId)) {
                                boolean optional = io.casehub.desiredstate.yaml.model.YamlNode.isDependencyOptional(dep);
                                if (!optional) {
                                    throw new IllegalStateException("Node '" + nodeId
                                                                    + "' depends on excluded conditional node '" + depId + "'");
                                }
                                continue;
                            }
                            phaseDeps.add(new io.casehub.desiredstate.api.Dependency(
                                    NodeId.of(nodeId), NodeId.of(depId)));
                        }
                    }
                }

                List<DesiredNode> allNodes = new ArrayList<>();
                for (DesiredNode cf : carryForwardNodes) {
                    if (!phaseNodeIds.contains(cf.id().value())) {
                        allNodes.add(cf);
                    }
                }
                allNodes.addAll(phaseNodes);

                for (io.casehub.desiredstate.api.Dependency cfDep : carryForwardDeps) {
                    boolean fromInPhase = allNodes.stream()
                                                  .anyMatch(n -> n.id().equals(cfDep.from()));
                    boolean toInPhase = allNodes.stream()
                                                .anyMatch(n -> n.id().equals(cfDep.to()));
                    if (fromInPhase && toInPhase
                        && !phaseNodeIds.contains(cfDep.from().value())) {
                        phaseDeps.add(cfDep);
                    }
                }

                io.casehub.desiredstate.api.DesiredStateGraph phaseGraph = factory.of(allNodes, phaseDeps);

                if (!yamlGraph.rules().isEmpty()) {
                    List<io.casehub.desiredstate.annotations.runtime.ResolvedRule> resolvedRules =
                            new ArrayList<>();
                    for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlRule> ruleEntry :
                            yamlGraph.rules().entrySet()) {
                        resolvedRules.add(YamlRuleConverter.toDeclarativeRule(
                                ruleEntry.getKey(), ruleEntry.getValue(), resolver, registry));
                    }
                    phaseGraph = new io.casehub.desiredstate.annotations.runtime.GraphRuleEngine()
                                         .evaluate(phaseGraph, resolvedRules);
                }

                if (!invariants.isEmpty()) {
                    new io.casehub.desiredstate.annotations.runtime.GraphInvariantEngine()
                            .validate(phaseGraph, invariants);
                }

                io.casehub.desiredstate.api.CompletionCondition cc =
                        resolveCompletionCondition(yamlPhase.completionCondition());

                phases.add(new io.casehub.desiredstate.api.Phase(yamlPhase.id(), phaseGraph, cc));

                carryForwardNodes = new ArrayList<>(phaseGraph.nodes().values());
                carryForwardDeps  = new ArrayList<>(phaseGraph.dependencies());
            }

            return CompilationResult.lifecycle(phases);
        });
    }

    private static io.casehub.desiredstate.api.CompletionCondition resolveCompletionCondition(
            Object condition) {
        if (condition instanceof String s) {
            return switch (s) {
                case "allPresent" -> io.casehub.desiredstate.api.CompletionCondition.allPresent();
                case "never" -> io.casehub.desiredstate.api.CompletionCondition.never();
                default -> throw new IllegalArgumentException(
                        "Unknown completionCondition: " + s);
            };
        }
        if (condition instanceof Map<?, ?> m) {
            String beanName = (String) m.get("bean");
            throw new UnsupportedOperationException(
                    "CDI bean completionCondition '" + beanName
                    + "' requires Quarkus runtime — use allPresent or never in unit tests");
        }
        throw new IllegalArgumentException("Invalid completionCondition: " + condition);
    }


    private static String findTypeNameForClass(Map<String, String> typeRegistry, String className) {
        for (Map.Entry<String, String> entry : typeRegistry.entrySet()) {
            if (entry.getValue().equals(className)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
