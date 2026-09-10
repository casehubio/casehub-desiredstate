package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.runtime.StepExecutionException;

import java.util.Map;

public class AssertPrimitive implements StepPrimitive {

    private final PluginInterpolator interpolator = new PluginInterpolator();

    @Override
    public String name() {
        return "assert";
    }

    @Override
    public StepResult execute(StepParameters params, StepContext context) {
        String condition = params.getString("condition");
        if (condition == null) {
            throw new StepExecutionException("assert: 'condition' parameter is required");
        }

        String message = params.getString("message");
        if (message != null) {
            message = interpolator.interpolate(message, context);
        }

        if (!interpolator.evaluateCondition(condition, context)) {
            throw new StepExecutionException(
                message != null ? message : "Assertion failed: " + condition);
        }

        return StepResult.of(Map.of("passed", true));
    }
}
