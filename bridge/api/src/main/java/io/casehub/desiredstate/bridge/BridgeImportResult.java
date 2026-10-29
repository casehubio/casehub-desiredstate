package io.casehub.desiredstate.bridge;

import java.util.Map;

public sealed interface BridgeImportResult {
    record Success(Map<String, Object> state) implements BridgeImportResult {}
    record Failed(String reason) implements BridgeImportResult {}
}
