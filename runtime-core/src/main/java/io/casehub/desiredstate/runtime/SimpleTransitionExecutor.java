package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;

import java.util.LinkedHashMap;
import java.util.Map;

public class SimpleTransitionExecutor implements TransitionExecutor {

    private final NodeStepExecutor nodeStepExecutor;

    public SimpleTransitionExecutor(NodeStepExecutor nodeStepExecutor) {
        this.nodeStepExecutor = nodeStepExecutor;
    }

    @Override
    public TransitionResult execute(TransitionPlan plan, String tenancyId) {
        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();

        for (OrderedStep step : plan.flatRemovals()) {
            StepOutcome outcome = nodeStepExecutor.execute(step.node(), step.action(), plan.before(), tenancyId);
            outcomes.put(step.node().id(), outcome);
        }

        for (OrderedStep step : plan.flatSuspensions()) {
            StepOutcome outcome = nodeStepExecutor.execute(step.node(), step.action(), plan.before(), tenancyId);
            outcomes.put(step.node().id(), outcome);
        }

        for (OrderedStep step : plan.flatResumptions()) {
            StepOutcome outcome = nodeStepExecutor.execute(step.node(), step.action(), plan.after(), tenancyId);
            outcomes.put(step.node().id(), outcome);
        }

        for (OrderedStep step : plan.flatAdditions()) {
            StepOutcome outcome = nodeStepExecutor.execute(step.node(), step.action(), plan.after(), tenancyId);
            outcomes.put(step.node().id(), outcome);
        }

        return new TransitionResult(outcomes);
    }
}
