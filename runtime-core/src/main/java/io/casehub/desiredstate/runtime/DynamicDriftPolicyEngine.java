package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.DriftPolicyEvaluator;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DynamicDriftPolicyEngine implements DriftPolicyEvaluator {

    private final CopyOnWriteArrayList<DriftPolicy> policies;

    public DynamicDriftPolicyEngine(List<DriftPolicy> staticPolicies) {
        this.policies = new CopyOnWriteArrayList<>(staticPolicies);
    }

    public void addPolicy(DriftPolicy policy) {
        policies.add(policy);
    }

    public void removePolicy(DriftPolicy policy) {
        policies.remove(policy);
    }

    @Override
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
