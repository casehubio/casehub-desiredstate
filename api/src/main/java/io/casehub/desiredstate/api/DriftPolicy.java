package io.casehub.desiredstate.api;

public interface DriftPolicy {
    DriftDecision evaluate(NodeId nodeId, NodeStatus status,
                           DesiredNode node, DriftContext context);
}
