package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.OrderedStep;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionExecutor;
import io.casehub.desiredstate.api.TransitionPlan;
import io.casehub.desiredstate.api.TransitionResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MockTransitionExecutor implements TransitionExecutor {

    public final CopyOnWriteArrayList<TransitionPlan> executedPlans = new CopyOnWriteArrayList<>();
    public final Set<NodeId> failNodes = ConcurrentHashMap.newKeySet();
    public final Set<NodeId> failDeprovisionNodes = ConcurrentHashMap.newKeySet();
    public final Set<NodeId> rejectNodes = ConcurrentHashMap.newKeySet();

    @Override
    public TransitionResult execute(TransitionPlan plan, String tenancyId) {
        executedPlans.add(plan);

        Map<NodeId, StepOutcome> outcomes = new LinkedHashMap<>();
        for (OrderedStep step : plan.removals()) {
            if (failDeprovisionNodes.contains(step.node().id())) {
                outcomes.put(step.node().id(), new StepOutcome.Failed("test deprovision failure"));
            } else {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
        }
        for (OrderedStep step : plan.additions()) {
            if (rejectNodes.contains(step.node().id())) {
                outcomes.put(step.node().id(), new StepOutcome.Rejected("test rejection"));
            } else if (failNodes.contains(step.node().id())) {
                outcomes.put(step.node().id(), new StepOutcome.Failed("test failure"));
            } else {
                outcomes.put(step.node().id(), new StepOutcome.Succeeded());
            }
        }
        return new TransitionResult(outcomes);
    }
}
