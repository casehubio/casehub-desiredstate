package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;

import java.util.Map;

public class CompareStatePrimitive implements Action {

    private final ConditionEvaluator conditionEvaluator;

    public CompareStatePrimitive(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    public Result execute(Map<String, Object> parameters, ServiceRegistry services) {
        String absentWhen  = (String) parameters.get("absent-when");
        String driftedWhen = (String) parameters.get("drifted-when");
        String presentWhen = (String) parameters.get("present-when");

        if (absentWhen != null && conditionEvaluator.evaluate(absentWhen)) {
            return Result.of(Map.of("nodeStatus", "ABSENT"));
        }
        if (driftedWhen != null && conditionEvaluator.evaluate(driftedWhen)) {
            return Result.of(Map.of("nodeStatus", "DRIFTED"));
        }
        if (presentWhen != null && conditionEvaluator.evaluate(presentWhen)) {
            return Result.of(Map.of("nodeStatus", "PRESENT"));
        }
        return Result.of(Map.of("nodeStatus", "UNKNOWN"));
    }
}
