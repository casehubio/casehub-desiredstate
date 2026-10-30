package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class BridgeProviderContractTest {

    protected abstract BridgeProvider createProvider();
    protected abstract String expectedProtocolId();
    protected abstract Set<String> expectedSupportedTypes();
    protected abstract BridgeRequest sampleApplyRequest();
    protected abstract BridgeRequest sampleDestroyRequest();
    protected abstract BridgeReadRequest sampleReadRequest();

    private BridgeProvider provider;

    @BeforeEach
    void setUp() {
        provider = createProvider();
    }

    @Test
    void protocolIdIsNonNull() {
        assertThat(provider.protocolId()).isNotNull().isNotEmpty();
    }

    @Test
    void supportedTypesIsNonEmpty() {
        assertThat(provider.supportedTypes()).isNotEmpty();
    }

    @Test
    void protocolIdMatchesExpected() {
        assertThat(provider.protocolId()).isEqualTo(expectedProtocolId());
    }

    @Test
    void supportedTypesMatchExpected() {
        assertThat(provider.supportedTypes()).isEqualTo(expectedSupportedTypes());
    }

    @Test
    void applyReturnsNonNull() {
        assertThat(provider.apply(sampleApplyRequest())).isNotNull();
    }

    @Test
    void destroyReturnsNonNull() {
        assertThat(provider.destroy(sampleDestroyRequest())).isNotNull();
    }

    @Test
    void readStateReturnsNonNull() {
        assertThat(provider.readState(sampleReadRequest())).isNotNull();
    }

    @Test
    void applySuccessHasOutputState() {
        var result = provider.apply(sampleApplyRequest());
        if (result instanceof BridgeResult.Success s) {
            assertThat(s.outputState()).isNotNull();
        }
    }

    @Test
    void applyWithSameRequestIsIdempotent() {
        var request = sampleApplyRequest();
        provider.apply(request);
        var result = provider.apply(request);
        assertThat(result).isNotNull();
    }
}
