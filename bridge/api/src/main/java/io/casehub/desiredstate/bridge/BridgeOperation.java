package io.casehub.desiredstate.bridge;

import java.util.Map;

public sealed interface BridgeOperation {
    record Apply(String typeId, String resourceId, Map<String, Object> spec) implements BridgeOperation {}
    record Destroy(String typeId, String resourceId) implements BridgeOperation {}
    record ReadState(String typeId, String resourceId) implements BridgeOperation {}
    record Plan(String typeId, String resourceId, Map<String, Object> spec) implements BridgeOperation {}
    record ApplyPlan(String planId, Object rawPlan) implements BridgeOperation {}
}
