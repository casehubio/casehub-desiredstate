package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.FaultPolicy;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.yaml.core.module.YamlModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class YamlDiscovery {

    public List<BeanRegistration> discover(
            List<YamlGraph> graphs,
            Map<String, String> typeRegistry,
            Map<String, YamlModule> modules) {

        List<BeanRegistration> beans = new ArrayList<>();

        for (YamlGraph yamlGraph : graphs) {
            String ns = yamlGraph.desiredState().namespace();
            String name = yamlGraph.desiredState().name();
            Map<String, Object> variables = yamlGraph.variables() != null
                ? yamlGraph.variables() : Map.of();
            List<ResolvedInvariant> invariants = buildInvariants(yamlGraph.invariants());

            GoalCompiler<?> compiler;
            if (yamlGraph.lifecycle() != null) {
                compiler = YamlGoalCompilerFactory.createLifecycle(
                    yamlGraph, typeRegistry, variables, invariants);
            } else {
                GraphDescriptor descriptor = toGraphDescriptor(yamlGraph, typeRegistry);
                compiler = YamlGoalCompilerFactory.create(
                    descriptor, typeRegistry, variables,
                    invariants, yamlGraph, modules, List.of(), List.of());
            }
            beans.add(new BeanRegistration(
                "goalCompiler_yaml_" + ns + "_" + name,
                GoalCompiler.class, compiler));

            for (int i = 0; i < yamlGraph.faultPolicy().size(); i++) {
                YamlFaultPolicy yamlPolicy = yamlGraph.faultPolicy().get(i);
                ThresholdFaultPolicy policy = YamlFaultPolicyBuilder.build(
                    yamlPolicy, typeRegistry, new InMemoryFaultCountStore());
                beans.add(new BeanRegistration(
                    "faultPolicy_yaml_" + ns + "_" + yamlPolicy.namespace(),
                    FaultPolicy.class, policy));
            }
        }

        return beans;
    }

    @SuppressWarnings("unchecked")
    private List<ResolvedInvariant> buildInvariants(Map<String, YamlInvariant> yamlInvariants) {
        List<ResolvedInvariant> invariants = new ArrayList<>();
        for (Map.Entry<String, YamlInvariant> entry : yamlInvariants.entrySet()) {
            invariants.add(YamlInvariantConverter.toDeclarativeInvariant(entry.getKey(), entry.getValue()));
        }
        return invariants;
    }

    private GraphDescriptor toGraphDescriptor(YamlGraph yamlGraph, Map<String, String> typeRegistry) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        List<DependencyDescriptor> deps = new ArrayList<>();

        for (Map.Entry<String, YamlNode> entry : yamlGraph.nodes().entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String specClassName = typeRegistry.get(yamlNode.type());

            nodes.add(new NodeDescriptor.InlineNode(
                nodeId, specClassName,
                yamlNode.spec() != null ? yamlNode.spec() : Map.of(),
                yamlNode.humanGating()));

            for (String dep : yamlNode.dependencyNodeIds()) {
                deps.add(new DependencyDescriptor(nodeId, dep));
            }
        }

        return new GraphDescriptor(
            yamlGraph.desiredState().namespace(),
            yamlGraph.desiredState().name(),
            null, null, nodes, deps,
            List.of(), null, List.of(), List.of());
    }
}
