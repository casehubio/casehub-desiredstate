package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;

import java.util.List;

public class DriftPolicyEngine {

    private final List<DriftPolicy> policies;

    public DriftPolicyEngine(List<DriftPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public DriftDecision evaluate(NodeId nodeId, NodeStatus status,
                                  DesiredNode node, DriftContext context) {
        for (DriftPolicy policy : policies) {
            DriftDecision decision = policy.evaluate(nodeId, status, node, context);
            if (decision instanceof DriftDecision.Exempt) {
                return decision;
            }
        }
        return DriftDecision.reconcile();
    }
}
