package io.casehub.desiredstate.api;

public interface LifecycleCanceller {
    void cancelLifecycle(String tenancyId);
}
