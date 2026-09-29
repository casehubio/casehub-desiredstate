package io.casehub.desiredstate.api;

import java.util.Objects;

public sealed interface TransitionAction {

    record EmitEvent(String eventType) implements TransitionAction {
        public EmitEvent {
            Objects.requireNonNull(eventType, "eventType must not be null");
        }
    }
}
