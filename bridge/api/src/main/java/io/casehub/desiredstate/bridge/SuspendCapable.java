package io.casehub.desiredstate.bridge;

public interface SuspendCapable {
    BridgeResult suspend(BridgeRequest request);
    BridgeResult resume(BridgeRequest request);
}
