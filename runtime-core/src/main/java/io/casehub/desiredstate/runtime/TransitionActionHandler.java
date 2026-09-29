package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeLifecycleState;
import io.casehub.desiredstate.api.TransitionAction;

@FunctionalInterface
public interface TransitionActionHandler {
    void execute(TransitionAction action, NodeId nodeId, NodeLifecycleState state, String tenancyId);
}
