package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class SuspendCapableContractTest {

    protected abstract SuspendCapable createSuspendCapableProvider();
    protected abstract BridgeRequest sampleSuspendRequest();

    private SuspendCapable provider;

    @BeforeEach
    void setUp() {
        provider = createSuspendCapableProvider();
    }

    @Test
    void suspendReturnsNonNull() {
        assertThat(provider.suspend(sampleSuspendRequest())).isNotNull();
    }

    @Test
    void resumeReturnsNonNull() {
        assertThat(provider.resume(sampleSuspendRequest())).isNotNull();
    }
}
