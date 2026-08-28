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
import java.util.List;
import java.util.Map;

@Recorder
public class YamlGraphRecorder {

    private static final Logger LOG = Logger.getLogger(YamlGraphRecorder.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants) {

        ObjectMapper     mapper   = new ObjectMapper();
        NodeSpecRegistry registry = NodeSpecRegistry.of(typeRegistryMap);

        return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
            VariableResolver resolver = new VariableResolver(inlineVariables, null, null);

            List<DesiredNode> nodes = new ArrayList<>();
            for (NodeDescriptor nd : descriptor.nodes()) {
                if (nd instanceof NodeDescriptor.InlineNode in) {
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
                deps.add(new Dependency(NodeId.of(dd.from()), NodeId.of(dd.to())));
            }

            DesiredStateGraph graph = factory.of(nodes, deps);

            if (!invariants.isEmpty()) {
                new GraphInvariantEngine().validate(graph, invariants);
            }

            return CompilationResult.single(graph);
        });
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
