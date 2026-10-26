package io.casehub.desiredstate.api;

public interface DriftPolicyEvaluator {
    DriftDecision evaluate(NodeId nodeId, NodeStatus status,
                           DesiredNode node, DriftContext context);
}
