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
        return createYamlGoalCompiler(descriptor, typeRegistryMap, inlineVariables, invariants, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants,
            io.casehub.desiredstate.yaml.model.YamlGraph yamlGraph) {

        ObjectMapper     mapper   = new ObjectMapper();
        NodeSpecRegistry registry = NodeSpecRegistry.of(typeRegistryMap);

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            VariableResolver resolver = new VariableResolver(inlineVariables, null, null);

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

            DesiredStateGraph graph = factory.of(nodes, deps);

            if (yamlGraph != null && !yamlGraph.rules().isEmpty()) {
                List<io.casehub.desiredstate.annotations.runtime.ResolvedRule> resolvedRules =
                        new ArrayList<>();
                for (Map.Entry<String, io.casehub.desiredstate.yaml.model.YamlRule> ruleEntry :
                        yamlGraph.rules().entrySet()) {
                    resolvedRules.add(YamlRuleConverter.toDeclarativeRule(
                            ruleEntry.getKey(), ruleEntry.getValue(), resolver, registry));
                }
                graph = new io.casehub.desiredstate.annotations.runtime.GraphRuleEngine()
                        .evaluate(graph, resolvedRules);
            }

            if (!invariants.isEmpty()) {
                new GraphInvariantEngine().validate(graph, invariants);
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

    private static boolean isTruthy(String value) {
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


    private static String findTypeNameForClass(Map<String, String> typeRegistry, String className) {
        for (Map.Entry<String, String> entry : typeRegistry.entrySet()) {
            if (entry.getValue().equals(className)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
