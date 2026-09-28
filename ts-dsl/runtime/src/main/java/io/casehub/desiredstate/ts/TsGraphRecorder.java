package io.casehub.desiredstate.ts;

import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.GoalCompiler;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

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
        return new RuntimeValue<>(TsGoalCompilerFactory.create(
                descriptor, typeRegistryMap, invariants,
                crossSurfaceRuleDescriptors, crossSurfaceInvariantDescriptors));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createTsLifecycleGoalCompiler(
            TsLifecycleEnvelope envelope,
            Map<String, String> typeRegistryMap,
            List<ResolvedInvariant> invariants) {
        return new RuntimeValue<>(TsGoalCompilerFactory.createLifecycle(
                envelope, typeRegistryMap, invariants));
    }
}
