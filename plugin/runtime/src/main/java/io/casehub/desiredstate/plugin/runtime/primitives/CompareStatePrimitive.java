package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.ServiceRegistry;
import io.casehub.yaml.plugin.api.StepAction;
import io.casehub.yaml.plugin.api.StepResult;

import java.util.Map;

public class CompareStatePrimitive implements StepAction {

    private final ConditionEvaluator conditionEvaluator;

    public CompareStatePrimitive(ConditionEvaluator conditionEvaluator) {
        this.conditionEvaluator = conditionEvaluator;
    }

    @Override
    public StepResult execute(Map<String, Object> parameters, ServiceRegistry services) {
        String absentWhen  = (String) parameters.get("absent-when");
        String driftedWhen = (String) parameters.get("drifted-when");
        String presentWhen = (String) parameters.get("present-when");

        if (absentWhen != null && conditionEvaluator.evaluate(absentWhen)) {
            return StepResult.of(Map.of("nodeStatus", "ABSENT"));
        }
        if (driftedWhen != null && conditionEvaluator.evaluate(driftedWhen)) {
            return StepResult.of(Map.of("nodeStatus", "DRIFTED"));
        }
        if (presentWhen != null && conditionEvaluator.evaluate(presentWhen)) {
            return StepResult.of(Map.of("nodeStatus", "PRESENT"));
        }
        return StepResult.of(Map.of("nodeStatus", "UNKNOWN"));
    }
}
