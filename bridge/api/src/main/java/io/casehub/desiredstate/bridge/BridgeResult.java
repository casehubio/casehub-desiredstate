package io.casehub.desiredstate.bridge;

import java.util.Map;

public sealed interface BridgeResult {
    record Success(Map<String, Object> outputState) implements BridgeResult {}
    record Failed(String reason, BridgeErrorCategory category) implements BridgeResult {}
    record PendingApproval(String planReference) implements BridgeResult {}
}
