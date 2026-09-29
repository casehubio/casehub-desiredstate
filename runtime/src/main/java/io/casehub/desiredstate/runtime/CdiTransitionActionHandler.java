package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.LifecycleStateEnteredData;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeLifecycleState;
import io.casehub.desiredstate.api.TransitionAction;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CdiTransitionActionHandler implements TransitionActionHandler {

    private static final Logger LOG = Logger.getLogger(CdiTransitionActionHandler.class.getName());

    private final ReconciliationEventEmitter emitter;
    private final Event<CloudEvent> cloudEventSink;

    protected CdiTransitionActionHandler() {
        this.emitter = null;
        this.cloudEventSink = null;
    }

    @Inject
    public CdiTransitionActionHandler(ReconciliationEventEmitter emitter,
                                       Event<CloudEvent> cloudEventSink) {
        this.emitter = emitter;
        this.cloudEventSink = cloudEventSink;
    }

    @Override
    public void execute(TransitionAction action, NodeId nodeId, NodeLifecycleState state, String tenancyId) {
        if (action instanceof TransitionAction.EmitEvent emit) {
            try {
                var data = new LifecycleStateEnteredData(
                    tenancyId, nodeId.value(), null, state.name(), null, emit.eventType());
                CloudEvent event = emitter.lifecycleStateEntered(data);
                cloudEventSink.fire(event);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to emit lifecycle event for node {0}: {1}",
                    new Object[]{nodeId.value(), e.getMessage()});
            }
        }
    }
}
