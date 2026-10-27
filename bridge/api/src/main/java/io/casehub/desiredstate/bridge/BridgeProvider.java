package io.casehub.desiredstate.bridge;

import java.util.Set;

public interface BridgeProvider {
    String protocolId();
    Set<String> supportedTypes();
    BridgeResult apply(BridgeRequest request);
    BridgeResult destroy(BridgeRequest request);
    BridgeStateResult readState(BridgeReadRequest request);
}
