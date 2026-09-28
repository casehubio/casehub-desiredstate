package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.InMemoryFaultCountStore;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.yaml.core.module.YamlModule;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

import java.util.List;
import java.util.Map;

@Recorder
public class YamlGraphRecorder {

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
            Map<String, io.casehub.yaml.core.module.YamlModule> availableModules) {
        return createYamlGoalCompiler(descriptor, typeRegistryMap, inlineVariables,
                invariants, yamlGraph, availableModules, List.of(), List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlGoalCompiler(
            GraphDescriptor descriptor,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants,
            YamlGraph yamlGraph,
            Map<String, YamlModule> availableModules,
            List<GraphRuleDescriptor> crossSurfaceRuleDescriptors,
            List<GraphInvariantDescriptor> crossSurfaceInvariantDescriptors) {
        return new RuntimeValue<>(YamlGoalCompilerFactory.create(
                descriptor, typeRegistryMap, inlineVariables, invariants,
                yamlGraph, availableModules, crossSurfaceRuleDescriptors,
                crossSurfaceInvariantDescriptors));
    }

    @SuppressWarnings("rawtypes")
    public RuntimeValue<ThresholdFaultPolicy> createYamlFaultPolicy(
            YamlFaultPolicy yamlPolicy,
            Map<String, String> typeRegistryMap) {
        return new RuntimeValue<>(YamlFaultPolicyBuilder.build(
                yamlPolicy, typeRegistryMap, new InMemoryFaultCountStore()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createYamlLifecycleGoalCompiler(
            YamlGraph yamlGraph,
            Map<String, String> typeRegistryMap,
            Map<String, String> inlineVariables,
            List<ResolvedInvariant> invariants) {
        return new RuntimeValue<>(YamlGoalCompilerFactory.createLifecycle(
                yamlGraph, typeRegistryMap, inlineVariables, invariants));
    }
}
