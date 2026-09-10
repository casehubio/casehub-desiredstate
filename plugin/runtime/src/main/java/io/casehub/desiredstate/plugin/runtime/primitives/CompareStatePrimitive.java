package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;

import java.util.Map;

public class CompareStatePrimitive implements StepPrimitive {

    private final PluginInterpolator interpolator = new PluginInterpolator();

    @Override
    public String name() {
        return "compare-state";
    }

    @Override
    public StepResult execute(StepParameters params, StepContext context) {
        String absentWhen = params.getString("absent-when");
        String driftedWhen = params.getString("drifted-when");
        String presentWhen = params.getString("present-when");

        if (absentWhen != null && interpolator.evaluateCondition(absentWhen, context)) {
            return StepResult.of(Map.of("nodeStatus", "ABSENT"));
        }
        if (driftedWhen != null && interpolator.evaluateCondition(driftedWhen, context)) {
            return StepResult.of(Map.of("nodeStatus", "DRIFTED"));
        }
        if (presentWhen != null && interpolator.evaluateCondition(presentWhen, context)) {
            return StepResult.of(Map.of("nodeStatus", "PRESENT"));
        }
        return StepResult.of(Map.of("nodeStatus", "UNKNOWN"));
    }
}
