package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class DesiredStateGraphRecorder {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createGoalCompiler(GraphDescriptor descriptor) {
        return new RuntimeValue<>(GoalCompilerFactory.create(descriptor));
    }

    public RuntimeValue<ThresholdFaultPolicy> createFaultPolicy(
            FaultPolicyDescriptor descriptor, String implClassName) {
        return new RuntimeValue<>(FaultPolicyFactory.create(descriptor, implClassName));
    }
}
