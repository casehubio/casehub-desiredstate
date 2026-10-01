package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.DesiredStateGraphAdapter;
import io.casehub.desiredstate.annotations.runtime.DesiredStateGraphView;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptorResolver;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantEngine;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleEngine;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.annotations.runtime.ResolvedRule;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.CompletionCondition;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HookDescriptor;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderingConstraint;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.model.YamlOrderingConstraint;
import io.casehub.desiredstate.yaml.model.YamlPhase;
import io.casehub.desiredstate.yaml.model.YamlRule;
import io.casehub.desiredstate.yaml.registry.NodeSpecRegistry;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.core.foreach.ExpansionResult;
import io.casehub.yaml.core.foreach.ForEachExpander;
import io.casehub.yaml.core.foreach.IterationValueExpander;
import io.casehub.yaml.core.module.ModuleExpander;
import io.casehub.yaml.core.module.TypedExpandedModule;
import io.casehub.yaml.core.module.YamlModule;
import io.casehub.yaml.core.resolver.VariableResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral factory for creating GoalCompiler instances from YAML graph descriptors.
 * Extracted from YamlGraphRecorder for use by both Quarkus (via Recorder) and Spring (via auto-config).
 */
public final class YamlGoalCompilerFactory {

    private static final ConditionEvaluator CONDITION = new ConditionEvaluator(null);

    private YamlGoalCompilerFactory() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static GoalCompiler<?> create(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, Object> inlineVariables,
            List<ResolvedInvariant> invariants,
            YamlGraph yamlGraph,
            Map<String, YamlModule> availableModules,
            List<GraphRuleDescriptor> crossSurfaceRuleDescriptors,
            List<GraphInvariantDescriptor> crossSurfaceInvariantDescriptors) {

        ObjectMapper mapper = new ObjectMapper();
        NodeSpecRegistry registry = NodeSpecRegistry.of(typeRegistryMap);

        return (GoalCompiler) (goals, factory) -> {
            VariableResolver resolver = new VariableResolver(Map.of(), Set.of("match", "fault"))
                    .withObjectScope("var", inlineVariables::get);

            Map<String, YamlNode> effectiveNodes =
                    yamlGraph != null ? new LinkedHashMap<>(yamlGraph.nodes()) : Map.of();
            Map<String, Map<String, String>> moduleScopes = Map.of();
            Map<String, YamlRule> promotedRules = Map.of();
            Map<String, YamlInvariant> promotedInvariants = Map.of();

            if (yamlGraph != null && !yamlGraph.imports().isEmpty() && availableModules != null) {
                DesiredStateModuleBridge bridge = new DesiredStateModuleBridge(mapper);
                DesiredStateModuleContent existingContent = new DesiredStateModuleContent(
                        effectiveNodes, Map.of(), Map.of());
                TypedExpandedModule<DesiredStateModuleContent> moduleExpanded =
                        ModuleExpander.expand(yamlGraph.imports(), availableModules, existingContent, bridge);
                effectiveNodes = moduleExpanded.content().nodes();
                moduleScopes = moduleExpanded.moduleScopes();
                promotedRules = moduleExpanded.content().rules();
                promotedInvariants = moduleExpanded.content().invariants();
                if (!moduleExpanded.moduleOutputs().isEmpty()) {
                    resolver = resolver.withScope("module", moduleExpanded.outputSource());
                }
            }

            Map<String, io.casehub.yaml.core.data.CsvDataSource> dataSources = Map.of();
            if (yamlGraph != null && !yamlGraph.data().isEmpty()) {
                dataSources = io.casehub.yaml.core.data.CsvDataSource.fromDataBlock(yamlGraph.data());
            }

            boolean hasForEach = effectiveNodes.values().stream().anyMatch(n -> n.forEach() != null);
            boolean hasModules = !moduleScopes.isEmpty();

            DesiredStateGraph graph;
            if (hasForEach || hasModules) {
                graph = buildForEachGraph(effectiveNodes, yamlGraph, registry, mapper, resolver, moduleScopes, dataSources, factory);
            } else {
                graph = buildSimpleGraph(descriptor, yamlGraph, registry, mapper, resolver, typeRegistryMap, factory);
            }

            graph = applyRules(graph, yamlGraph, promotedRules, crossSurfaceRuleDescriptors, resolver, registry);
            applyInvariants(graph, invariants, promotedInvariants, crossSurfaceInvariantDescriptors);
            graph = applyOrderingConstraints(graph, yamlGraph);

            return CompilationResult.single(graph);
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static GoalCompiler<?> createLifecycle(
            YamlGraph yamlGraph,
            Map<String, String> typeRegistryMap,
            Map<String, Object> inlineVariables,
            List<ResolvedInvariant> invariants) {

        ObjectMapper mapper = new ObjectMapper();
        NodeSpecRegistry registry = NodeSpecRegistry.of(typeRegistryMap);

        return (GoalCompiler) (goals, factory) -> {
            VariableResolver resolver = new VariableResolver(Map.of(), Set.of("match", "fault"))
                    .withObjectScope("var", inlineVariables::get);
            List<Phase> phases = new ArrayList<>();
            List<DesiredNode> carryForwardNodes = new ArrayList<>();
            List<Dependency> carryForwardDeps = new ArrayList<>();

            for (YamlPhase yamlPhase : yamlGraph.lifecycle().phases()) {
                boolean phaseHasForEach = yamlPhase.nodes().values().stream()
                        .anyMatch(n -> n.forEach() != null);

                List<DesiredNode> phaseNodes;
                List<Dependency> phaseDeps;
                Set<String> phaseNodeIds = new HashSet<>();

                if (phaseHasForEach) {
                    var adapter = new YamlNodeForEachAdapter();
                    ExpansionResult<YamlNode> expanded = ForEachExpander.expand(
                            yamlPhase.nodes(),
                            yamlGraph.iterations() != null ? yamlGraph.iterations() : Map.of(),
                            resolver, adapter, 1000, jsonArrayExpander(mapper));
                    phaseNodes = new ArrayList<>();
                    phaseDeps = new ArrayList<>();
                    for (Map.Entry<String, YamlNode> expEntry : expanded.elements().entrySet()) {
                        String expNodeId = expEntry.getKey();
                        YamlNode expYamlNode = expEntry.getValue();
                        Class<? extends NodeSpec> specClass = registry.resolve(expYamlNode.type());
                        NodeSpec spec = mapper.convertValue(expYamlNode.spec(), specClass);
                        VariableResolver nodeResolver = adapter.resolverFor(expNodeId);
                        phaseNodes.add(new DesiredNode(NodeId.of(expNodeId), spec, expYamlNode.humanGating(),
                                HookResolver.resolveHooks(expYamlNode, nodeResolver != null ? nodeResolver : resolver, expNodeId)));
                        phaseNodeIds.add(expNodeId);
                        for (Object dep : expYamlNode.dependsOn()) {
                            String depId = YamlNode.dependencyNodeId(dep);
                            if (!expanded.excludedIds().contains(depId)) {
                                phaseDeps.add(new Dependency(NodeId.of(expNodeId), NodeId.of(depId)));
                            }
                        }
                    }
                } else {
                    Set<String> excludedNodeIds = new HashSet<>();
                    phaseNodes = new ArrayList<>();
                    phaseDeps = new ArrayList<>();

                    for (Map.Entry<String, YamlNode> entry : yamlPhase.nodes().entrySet()) {
                        String nodeId = entry.getKey();
                        YamlNode yamlNode = entry.getValue();
                        if (yamlNode.when() != null) {
                            String resolved = resolver.resolveString(yamlNode.when(), nodeId);
                            if (!CONDITION.evaluate(resolved)) {
                                excludedNodeIds.add(nodeId);
                                continue;
                            }
                        }
                        Class<? extends NodeSpec> specClass = registry.resolve(yamlNode.type());
                        Map<String, Object> resolvedSpec = resolver.resolveMap(
                                yamlNode.spec() != null ? yamlNode.spec() : Map.of(), nodeId);
                        NodeSpec spec = mapper.convertValue(resolvedSpec, specClass);
                        phaseNodes.add(new DesiredNode(NodeId.of(nodeId), spec, yamlNode.humanGating(),
                                HookResolver.resolveHooks(yamlNode, resolver, nodeId)));
                        phaseNodeIds.add(nodeId);
                    }

                    for (Map.Entry<String, YamlNode> entry : yamlPhase.nodes().entrySet()) {
                        String nodeId = entry.getKey();
                        if (excludedNodeIds.contains(nodeId)) { continue; }
                        for (Object dep : entry.getValue().dependsOn()) {
                            String depId = YamlNode.dependencyNodeId(dep);
                            if (excludedNodeIds.contains(depId)) {
                                boolean optional = YamlNode.isDependencyOptional(dep);
                                if (!optional) {
                                    throw new IllegalStateException("Node '" + nodeId
                                            + "' depends on excluded conditional node '" + depId + "'");
                                }
                                continue;
                            }
                            phaseDeps.add(new Dependency(NodeId.of(nodeId), NodeId.of(depId)));
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

                for (Dependency cfDep : carryForwardDeps) {
                    boolean fromInPhase = allNodes.stream().anyMatch(n -> n.id().equals(cfDep.from()));
                    boolean toInPhase = allNodes.stream().anyMatch(n -> n.id().equals(cfDep.to()));
                    if (fromInPhase && toInPhase && !phaseNodeIds.contains(cfDep.from().value())) {
                        phaseDeps.add(cfDep);
                    }
                }

                DesiredStateGraph phaseGraph = factory.of(allNodes, phaseDeps);

                if (!yamlGraph.rules().isEmpty()) {
                    List<ResolvedRule> resolvedRules = new ArrayList<>();
                    for (Map.Entry<String, YamlRule> ruleEntry : yamlGraph.rules().entrySet()) {
                        resolvedRules.add(YamlRuleConverter.toDeclarativeRule(
                                ruleEntry.getKey(), ruleEntry.getValue(), resolver, registry));
                    }
                    var phRuleAdapter = new DesiredStateGraphAdapter();
                    var phRuleView = new DesiredStateGraphView(phaseGraph, phRuleAdapter);
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    var phEvaluated = new GraphRuleEngine().evaluate(phRuleView, (java.util.List) resolvedRules);
                    phaseGraph = ((DesiredStateGraphView) phEvaluated).graph();
                }

                if (!invariants.isEmpty()) {
                    var phInvAdapter = new DesiredStateGraphAdapter();
                    var phInvView = new DesiredStateGraphView(phaseGraph, phInvAdapter);
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    java.util.List phTypedInvariants = invariants;
                    new GraphInvariantEngine().validate(phInvView, phTypedInvariants);
                }

                phaseGraph = applyOrderingConstraints(phaseGraph, yamlGraph);
                CompletionCondition cc = resolveCompletionCondition(yamlPhase.completionCondition());
                phases.add(new Phase(yamlPhase.id(), phaseGraph, cc));

                carryForwardNodes = new ArrayList<>(phaseGraph.nodes().values());
                carryForwardDeps = new ArrayList<>(phaseGraph.dependencies());
            }

            return CompilationResult.lifecycle(phases);
        };
    }

    private static DesiredStateGraph buildForEachGraph(
            Map<String, YamlNode> effectiveNodes, YamlGraph yamlGraph,
            NodeSpecRegistry registry, ObjectMapper mapper,
            VariableResolver resolver, Map<String, Map<String, String>> moduleScopes,
            Map<String, io.casehub.yaml.core.data.CsvDataSource> dataSources,
            io.casehub.desiredstate.api.DesiredStateGraphFactory factory) {
        var adapter = new YamlNodeForEachAdapter(moduleScopes);
        Map<String, io.casehub.yaml.core.foreach.IterationGroup> iterations =
                yamlGraph != null && yamlGraph.iterations() != null ? yamlGraph.iterations() : Map.of();
        ExpansionResult<YamlNode> expanded;
        if (!dataSources.isEmpty()) {
            expanded = ForEachExpander.expand(
                    effectiveNodes, iterations, dataSources, resolver, adapter, 1000);
        } else {
            expanded = ForEachExpander.expand(
                    effectiveNodes, iterations, resolver, adapter, 1000, jsonArrayExpander(mapper));
        }
        List<DesiredNode> expNodes = new ArrayList<>();
        List<Dependency>  expDeps  = new ArrayList<>();
        for (Map.Entry<String, YamlNode> expEntry : expanded.elements().entrySet()) {
            String                    expNodeId    = expEntry.getKey();
            YamlNode                  expYamlNode  = expEntry.getValue();
            Class<? extends NodeSpec> specClass    = registry.resolve(expYamlNode.type());
            NodeSpec                  spec         = mapper.convertValue(expYamlNode.spec(), specClass);
            VariableResolver          nodeResolver = adapter.resolverFor(expNodeId);
            expNodes.add(new DesiredNode(NodeId.of(expNodeId), spec, expYamlNode.humanGating(),
                                         HookResolver.resolveHooks(expYamlNode, nodeResolver != null ? nodeResolver : resolver, expNodeId)));
            for (Object dep : expYamlNode.dependsOn()) {
                String depId = YamlNode.dependencyNodeId(dep);
                if (!expanded.excludedIds().contains(depId)) {
                    expDeps.add(new Dependency(NodeId.of(expNodeId), NodeId.of(depId)));
                }
            }
        }
        return factory.of(expNodes, expDeps);
    }

    private static DesiredStateGraph buildSimpleGraph(
            GraphDescriptor descriptor, YamlGraph yamlGraph,
            NodeSpecRegistry registry, ObjectMapper mapper,
            VariableResolver resolver, Map<String, String> typeRegistryMap,
            io.casehub.desiredstate.api.DesiredStateGraphFactory factory) {
        Set<String> excludedNodeIds = new HashSet<>();
        if (yamlGraph != null) {
            for (Map.Entry<String, YamlNode> entry : yamlGraph.nodes().entrySet()) {
                String nodeId = entry.getKey();
                YamlNode yamlNode = entry.getValue();
                if (yamlNode.when() != null) {
                    String resolved = resolver.resolveString(yamlNode.when(), nodeId);
                    if (!CONDITION.evaluate(resolved)) {
                        excludedNodeIds.add(nodeId);
                    }
                }
            }
        }

        List<DesiredNode> nodes = new ArrayList<>();
        for (NodeDescriptor nd : descriptor.nodes()) {
            if (nd instanceof NodeDescriptor.InlineNode in) {
                if (excludedNodeIds.contains(in.id())) { continue; }
                Class<? extends NodeSpec> specClass = registry.resolveByClassName(in.specClassName());
                Map<String, Object> resolved = resolver.resolveMap(in.specValues(), in.id());
                NodeSpec spec = mapper.convertValue(resolved, specClass);

                String expectedType = findTypeNameForClass(typeRegistryMap, in.specClassName());
                if (expectedType != null && !spec.nodeType().value().equals(expectedType)) {
                    throw new IllegalStateException(
                            "@NodeTypeId(\"" + expectedType + "\") diverges from nodeType()=\""
                                    + spec.nodeType().value() + "\" on " + specClass.getName());
                }

                HookDescriptor hooks = null;
                if (yamlGraph != null && yamlGraph.nodes().containsKey(in.id())) {
                    hooks = HookResolver.resolveHooks(yamlGraph.nodes().get(in.id()), resolver, in.id());
                }
                nodes.add(new DesiredNode(NodeId.of(in.id()), spec, in.humanGating(), hooks));
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

        return factory.of(nodes, deps);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DesiredStateGraph applyRules(
            DesiredStateGraph graph, YamlGraph yamlGraph,
            Map<String, YamlRule> promotedRules,
            List<GraphRuleDescriptor> crossSurfaceRuleDescriptors,
            VariableResolver resolver, NodeSpecRegistry registry) {
        Map<String, YamlRule> effectiveRules = new LinkedHashMap<>();
        if (yamlGraph != null) { effectiveRules.putAll(yamlGraph.rules()); }
        effectiveRules.putAll(promotedRules);

        List<ResolvedRule> allResolvedRules = new ArrayList<>();
        for (Map.Entry<String, YamlRule> ruleEntry : effectiveRules.entrySet()) {
            allResolvedRules.add(YamlRuleConverter.toDeclarativeRule(
                    ruleEntry.getKey(), ruleEntry.getValue(), resolver, registry));
        }
        if (crossSurfaceRuleDescriptors != null && !crossSurfaceRuleDescriptors.isEmpty()) {
            allResolvedRules.addAll(GraphDescriptorResolver.resolveRules(crossSurfaceRuleDescriptors));
        }
        if (!allResolvedRules.isEmpty()) {
            var ruleAdapter = new DesiredStateGraphAdapter();
            var ruleView = new DesiredStateGraphView(graph, ruleAdapter);
            var evaluated = new GraphRuleEngine().evaluate(ruleView, (java.util.List) allResolvedRules);
            graph = ((DesiredStateGraphView) evaluated).graph();
        }
        return graph;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyInvariants(
            DesiredStateGraph graph, List<ResolvedInvariant> invariants,
            Map<String, YamlInvariant> promotedInvariants,
            List<GraphInvariantDescriptor> crossSurfaceInvariantDescriptors) {
        List<ResolvedInvariant> effectiveInvariants = new ArrayList<>(invariants);
        for (Map.Entry<String, YamlInvariant> invEntry : promotedInvariants.entrySet()) {
            effectiveInvariants.add(YamlInvariantConverter.toDeclarativeInvariant(
                    invEntry.getKey(), invEntry.getValue()));
        }
        if (crossSurfaceInvariantDescriptors != null && !crossSurfaceInvariantDescriptors.isEmpty()) {
            effectiveInvariants.addAll(GraphDescriptorResolver.resolveInvariants(crossSurfaceInvariantDescriptors));
        }
        if (!effectiveInvariants.isEmpty()) {
            var invAdapter = new DesiredStateGraphAdapter();
            var invView = new DesiredStateGraphView(graph, invAdapter);
            java.util.List typedInvariants = effectiveInvariants;
            new GraphInvariantEngine().validate(invView, typedInvariants);
        }
    }

    static DesiredStateGraph applyOrderingConstraints(DesiredStateGraph graph, YamlGraph yamlGraph) {
        if (yamlGraph == null || yamlGraph.orderingConstraints().isEmpty()) {
            return graph;
        }
        Set<OrderingConstraint> constraints = new HashSet<>();
        for (YamlOrderingConstraint yc : yamlGraph.orderingConstraints()) {
            constraints.add(new OrderingConstraint(NodeType.of(yc.before()), NodeType.of(yc.after())));
        }
        return graph.withOrderingConstraints(constraints);
    }


    static boolean isOptionalDependency(YamlGraph yamlGraph, String fromNodeId, String toNodeId) {
        YamlNode node = yamlGraph.nodes().get(fromNodeId);
        if (node == null) { return false; }
        for (Object dep : node.dependsOn()) {
            String depId = YamlNode.dependencyNodeId(dep);
            if (depId.equals(toNodeId)) {
                return YamlNode.isDependencyOptional(dep);
            }
        }
        return false;
    }

    static CompletionCondition resolveCompletionCondition(Object condition) {
        if (condition instanceof String s) {
            return switch (s) {
                case "allPresent" -> CompletionCondition.allPresent();
                case "never" -> CompletionCondition.never();
                default -> throw new IllegalArgumentException("Unknown completionCondition: " + s);
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

    static IterationValueExpander jsonArrayExpander(ObjectMapper mapper) {
        return (value, ctx) -> {
            if (value.startsWith("[")) {
                try {
                    List<?> parsed = mapper.readValue(value, new TypeReference<List<?>>() {});
                    return parsed.stream().map(item -> {
                        if (!(item instanceof String)) {
                            throw new IllegalArgumentException("forEach group '" + ctx
                                    + "': values must be strings, got " + item.getClass().getSimpleName());
                        }
                        return (String) item;
                    }).toList();
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException("forEach group '" + ctx
                            + "': not a valid JSON array: " + value, e);
                }
            }
            return List.of(value);
        };
    }

    static String findTypeNameForClass(Map<String, String> typeRegistry, String className) {
        for (Map.Entry<String, String> entry : typeRegistry.entrySet()) {
            if (entry.getValue().equals(className)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
