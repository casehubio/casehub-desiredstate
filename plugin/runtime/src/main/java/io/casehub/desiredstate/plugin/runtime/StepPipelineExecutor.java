package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.model.PluginStepDef;

import java.util.List;

public class StepPipelineExecutor {

    private final PrimitiveRegistry registry;
    private final PluginInterpolator interpolator;

    public StepPipelineExecutor(PrimitiveRegistry registry,
                                PluginInterpolator interpolator) {
        this.registry = registry;
        this.interpolator = interpolator;
    }

    public StepResult execute(List<PluginStepDef> steps, StepContext context) {
        StepResult lastResult = StepResult.empty();

        for (int i = 0; i < steps.size(); i++) {
            PluginStepDef step = steps.get(i);

            if (step.when() != null
                    && !interpolator.evaluateCondition(step.when(), context)) {
                continue;
            }

            StepPrimitive primitive = registry.resolve(step.primitiveName());
            StepParameters params = StepParameters.of(step.parameters());

            lastResult = executeWithRetry(primitive, params, context, step, i);

            if (step.resultName() != null) {
                context.addResult(step.resultName(), lastResult);
            }
        }
        return lastResult;
    }

    public NodeStatus executeActualState(List<PluginStepDef> steps,
                                         StepContext context) {
        try {
            StepResult result = execute(steps, context);
            Object status = result.get("nodeStatus");
            if (status instanceof String s) {
                return NodeStatus.valueOf(s);
            }
            return NodeStatus.UNKNOWN;
        } catch (StepExecutionException e) {
            return NodeStatus.UNKNOWN;
        }
    }

    private StepResult executeWithRetry(StepPrimitive primitive,
                                        StepParameters params,
                                        StepContext context,
                                        PluginStepDef step,
                                        int stepIndex) {
        String onError = step.onError();
        int maxRetries = step.maxRetries();
        String backoff = step.backoff();

        if (!"retry".equals(onError)) {
            return executeSingle(primitive, params, context, step, stepIndex);
        }

        StepExecutionException lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return executeSingle(primitive, params, context, step, stepIndex);
            } catch (StepExecutionException e) {
                lastError = e;
                if (attempt < maxRetries) {
                    applyBackoff(backoff, attempt);
                }
            }
        }
        throw lastError;
    }

    private StepResult executeSingle(StepPrimitive primitive,
                                     StepParameters params,
                                     StepContext context,
                                     PluginStepDef step,
                                     int stepIndex) {
        try {
            return primitive.execute(params, context);
        } catch (StepExecutionException e) {
            String onError = step.onError();
            if ("skip".equals(onError)) {
                return StepResult.empty();
            }
            throw new StepExecutionException(
                "Step " + stepIndex + " (" + step.primitiveName() + ") failed: "
                    + e.getMessage(),
                e, null, stepIndex, step.primitiveName());
        } catch (RuntimeException e) {
            String onError = step.onError();
            if ("skip".equals(onError)) {
                return StepResult.empty();
            }
            throw new StepExecutionException(
                "Step " + stepIndex + " (" + step.primitiveName() + ") failed: "
                    + e.getMessage(),
                e, null, stepIndex, step.primitiveName());
        }
    }

    private void applyBackoff(String backoff, int attempt) {
        long delayMs;
        if (backoff != null && backoff.startsWith("exponential:")) {
            long base = parseDurationMs(backoff.substring("exponential:".length()));
            delayMs = base * (1L << attempt);
        } else if (backoff != null && backoff.startsWith("fixed:")) {
            delayMs = parseDurationMs(backoff.substring("fixed:".length()));
        } else {
            delayMs = 1000;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepExecutionException("Retry interrupted");
        }
    }

    private long parseDurationMs(String duration) {
        if (duration.endsWith("ms")) {
            return Long.parseLong(duration.substring(0, duration.length() - 2));
        }
        if (duration.endsWith("s")) {
            return Long.parseLong(duration.substring(0, duration.length() - 1)) * 1000;
        }
        return Long.parseLong(duration);
    }
}
