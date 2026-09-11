package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.step.StepContext;
import io.casehub.yaml.step.StepParameters;
import io.casehub.yaml.step.StepPrimitive;
import io.casehub.yaml.step.StepResult;
import io.casehub.yaml.step.expr.ExpressionEvaluator;

import java.util.Map;

public class CompareStatePrimitive implements StepPrimitive {

    @Override
    public String name() {
        return "compare-state";
    }

    @Override
    public StepResult execute(StepParameters params, StepContext context) {
        String absentWhen = params.getString("absent-when");
        String driftedWhen = params.getString("drifted-when");
        String presentWhen = params.getString("present-when");

        if (absentWhen != null && ExpressionEvaluator.evaluate(absentWhen, Map.of())) {
            return StepResult.of(Map.of("nodeStatus", "ABSENT"));
        }
        if (driftedWhen != null && ExpressionEvaluator.evaluate(driftedWhen, Map.of())) {
            return StepResult.of(Map.of("nodeStatus", "DRIFTED"));
        }
        if (presentWhen != null && ExpressionEvaluator.evaluate(presentWhen, Map.of())) {
            return StepResult.of(Map.of("nodeStatus", "PRESENT"));
        }
        return StepResult.of(Map.of("nodeStatus", "UNKNOWN"));
    }
}
