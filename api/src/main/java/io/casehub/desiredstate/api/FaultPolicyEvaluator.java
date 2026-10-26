package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicyEvaluator {
    List<GraphMutation<DesiredNode>> evaluate(String tenancyId, FaultEvent event,
                                              DesiredStateGraph current, ActualState actual);
}
