package io.casehub.desiredstate.bridge.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

record BridgeManifest(
    @JsonProperty("bridge") BridgeRoot bridge
) {
    record BridgeRoot(
        String protocol,
        String version,
        LifecycleDef lifecycle,
        List<TypeDef> types,
        String credentialRef,
        List<String> trustedKeyIds
    ) {}

    record LifecycleDef(
        String mode,
        String binary,
        List<String> args,
        HealthCheckDef healthCheck,
        RestartDef restart,
        String endpoint,
        PoolDef pool
    ) {}

    record HealthCheckDef(String type, String interval, String timeout) {}

    record RestartDef(String policy, int maxRetries, String backoff) {}

    record TypeDef(
        String id,
        Map<String, String> stateMapping,
        Map<String, ErrorMappingEntry> errorMapping
    ) {}

    record ErrorMappingEntry(String category) {}

    record PoolDef(int maxProcesses, String idleTimeout, String checkoutTimeout) {}
}
