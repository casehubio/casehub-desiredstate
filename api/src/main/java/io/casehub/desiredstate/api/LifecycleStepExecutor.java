package io.casehub.desiredstate.api;

public interface LifecycleStepExecutor {
    StepOutcome execute(LifecycleStep step, String tenancyId);
}
