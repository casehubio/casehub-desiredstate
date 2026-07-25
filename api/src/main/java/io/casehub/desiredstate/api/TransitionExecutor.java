package io.casehub.desiredstate.api;

public interface TransitionExecutor {
    TransitionResult execute(TransitionPlan plan, String tenancyId);
}
