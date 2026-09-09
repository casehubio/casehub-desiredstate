package io.casehub.desiredstate.plugin.api;

public interface StepPrimitive {

    String name();

    StepResult execute(StepParameters params, StepContext context);
}
