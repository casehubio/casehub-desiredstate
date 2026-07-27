package io.casehub.desiredstate.testing;

import java.time.Duration;

public final class TestTimeouts {
    public static final Duration AWAIT = Duration.ofSeconds(10);
    public static final Duration MUTINY_AWAIT = Duration.ofSeconds(5);

    private TestTimeouts() {}
}
