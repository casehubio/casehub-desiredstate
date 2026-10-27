package io.casehub.desiredstate.bridge;

import java.util.Map;

public sealed interface BridgeStateResult {
    record Found(Map<String, Object> state) implements BridgeStateResult {}
    record NotFound() implements BridgeStateResult {}
    record Error(String reason) implements BridgeStateResult {}
}
