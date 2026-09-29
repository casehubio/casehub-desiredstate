package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;

import java.util.List;
import java.util.Map;

final class ActualStateStepExecutor {

    private final StructuralStepEvaluator evaluator;
    private final StepRunner              runner;

    ActualStateStepExecutor(StructuralStepEvaluator evaluator, StepRunner runner) {
        this.evaluator = evaluator;
        this.runner    = runner;
    }

    NodeStatus execute(List<ResolvedStep> steps, VariableResolver resolver) {
        if (steps.isEmpty()) {
            return NodeStatus.UNKNOWN;
        }
        try {
            ResolvedStep block  = new ResolvedStep.BlockStep(null, steps, Map.of());
            Result       result = evaluator.evaluate(block, resolver, runner);
            if (result instanceof Result.Success s) {
                Object status = s.output().get("nodeStatus");
                if (status instanceof String statusStr) {
                    return NodeStatus.valueOf(statusStr);
                }
            }
            return NodeStatus.UNKNOWN;
        } catch (RuntimeException e) {
            return NodeStatus.UNKNOWN;
        }
    }
}
