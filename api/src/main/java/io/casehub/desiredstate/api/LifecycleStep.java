package io.casehub.desiredstate.api;

public sealed interface LifecycleStep {
    record Verify(String url, int timeoutSeconds) implements LifecycleStep {
        public Verify { if (timeoutSeconds <= 0) timeoutSeconds = 30; }
    }

    record Notify(String channel, String message) implements LifecycleStep {}

    record Wait(int seconds) implements LifecycleStep {}
}
