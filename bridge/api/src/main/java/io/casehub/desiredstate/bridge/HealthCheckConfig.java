package io.casehub.desiredstate.bridge;

import java.time.Duration;

public record HealthCheckConfig(HealthCheckType type, Duration interval, Duration timeout) {
    public HealthCheckConfig {
        if (interval == null) interval = Duration.ofSeconds(30);
        if (timeout == null) timeout = Duration.ofSeconds(5);
    }
}
