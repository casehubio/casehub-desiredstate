package io.casehub.desiredstate.bridge;

import java.util.List;

public record BridgeLifecycleConfig(
    LifecycleMode mode,
    String binaryPath,
    List<String> args,
    HealthCheckConfig healthCheck,
    RestartPolicy restartPolicy,
    String endpoint,
    PoolConfig poolConfig
) {
    public BridgeLifecycleConfig {
        if (args == null) args = List.of();
    }

    public BridgeLifecycleConfig(LifecycleMode mode, String binaryPath, List<String> args,
                                  HealthCheckConfig healthCheck, RestartPolicy restartPolicy,
                                  String endpoint) {
        this(mode, binaryPath, args, healthCheck, restartPolicy, endpoint, null);
    }
}
