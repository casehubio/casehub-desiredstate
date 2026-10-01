package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DriftContext;
import io.casehub.desiredstate.api.DriftDecision;
import io.casehub.desiredstate.api.DriftPolicy;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;

import java.util.concurrent.ConcurrentHashMap;

public class MockDriftPolicy implements DriftPolicy {

    private final ConcurrentHashMap<NodeId, DriftDecision> decisions = new ConcurrentHashMap<>();
    private DriftDecision defaultDecision = DriftDecision.reconcile();

    public void setDecision(NodeId nodeId, DriftDecision decision) {
        decisions.put(nodeId, decision);
    }

    public void setDefaultDecision(DriftDecision decision) {
        this.defaultDecision = decision;
    }

    @Override
    public DriftDecision evaluate(NodeId nodeId, NodeStatus status,
                                  DesiredNode node, DriftContext context) {
        return decisions.getOrDefault(nodeId, defaultDecision);
    }
}
