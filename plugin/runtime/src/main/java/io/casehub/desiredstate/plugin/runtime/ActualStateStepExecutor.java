package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.step.StepContext;
import io.casehub.yaml.step.StepDef;
import io.casehub.yaml.step.StepExecutionException;
import io.casehub.yaml.step.StepPipelineExecutor;
import io.casehub.yaml.step.StepResult;

import java.util.List;

final class ActualStateStepExecutor {

    private final StepPipelineExecutor executor;

    ActualStateStepExecutor(StepPipelineExecutor executor) {
        this.executor = executor;
    }

    NodeStatus execute(List<StepDef> steps, StepContext context,
                       VariableResolver resolver) {
        try {
            StepResult result = executor.execute(steps, context, resolver);
            Object status = result.get("nodeStatus");
            if (status instanceof String s) {
                return NodeStatus.valueOf(s);
            }
            return NodeStatus.UNKNOWN;
        } catch (StepExecutionException e) {
            return NodeStatus.UNKNOWN;
        }
    }
}
