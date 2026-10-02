package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeLifecycleState;
import io.casehub.desiredstate.api.TransitionAction;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class CdiTransitionActionHandlerTest {

    private ReconciliationEventEmitter emitter;
    private List<CloudEvent> firedEvents;
    private CdiTransitionActionHandler handler;

    @BeforeEach
    void setUp() {
        emitter = new ReconciliationEventEmitter();
        firedEvents = new ArrayList<>();
        Event<CloudEvent> capturingEvent = new CapturingEvent(firedEvents);
        handler = new CdiTransitionActionHandler(emitter, capturingEvent);
    }

    @Test
    void emitEvent_firesCloudEventWithCorrectType() {
        var action = new TransitionAction.EmitEvent("app.deployed");

        handler.execute(action, NodeId.of("node-1"), NodeLifecycleState.PRESENT, "tenant-1");

        assertThat(firedEvents).hasSize(1);
        CloudEvent event = firedEvents.get(0);
        assertThat(event.getType()).isEqualTo("io.casehub.desiredstate.lifecycle.state-entered");
        assertThat(event.getSubject()).isEqualTo("node-1");
        assertThat(event.getExtension("tenancyid")).isEqualTo("tenant-1");
        assertThat(event.getExtension("customeventtype")).isEqualTo("app.deployed");
    }

    @Test
    void emitEvent_setsStateInExtension() {
        handler.execute(new TransitionAction.EmitEvent("node.ready"),
                NodeId.of("n1"), NodeLifecycleState.PROVISIONING, "t1");

        assertThat(firedEvents).hasSize(1);
        assertThat(firedEvents.get(0).getExtension("state")).isEqualTo("PROVISIONING");
    }

    @Test
    void emitEvent_eventSinkFailure_doesNotPropagate() {
        Event<CloudEvent> failingEvent = new CapturingEvent(firedEvents) {
            @Override
            public void fire(CloudEvent event) {
                throw new RuntimeException("CDI event bus down");
            }
        };
        var failingHandler = new CdiTransitionActionHandler(emitter, failingEvent);

        failingHandler.execute(new TransitionAction.EmitEvent("crash.test"),
                NodeId.of("n1"), NodeLifecycleState.PRESENT, "t1");

        // No exception propagated — silent failure with logging
    }

    private static class CapturingEvent implements Event<CloudEvent> {
        private final List<CloudEvent> captured;

        CapturingEvent(List<CloudEvent> captured) {
            this.captured = captured;
        }

        @Override
        public void fire(CloudEvent event) {
            captured.add(event);
        }

        @Override
        public <U extends CloudEvent> CompletionStage<U> fireAsync(U event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends CloudEvent> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Event<CloudEvent> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends CloudEvent> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends CloudEvent> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }
    }
}
