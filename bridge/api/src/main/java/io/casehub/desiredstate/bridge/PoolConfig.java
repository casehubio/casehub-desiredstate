package io.casehub.desiredstate.bridge;

import java.time.Duration;

public record PoolConfig(
    int maxProcesses,
    Duration idleTimeout,
    Duration checkoutTimeout
) {
    public static final PoolConfig DEFAULT = new PoolConfig(10,
        Duration.ofMinutes(5), Duration.ofSeconds(30));
}
