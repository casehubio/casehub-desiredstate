package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class ImportCapableContractTest {

    protected abstract ImportCapable createImportCapableProvider();
    protected abstract String sampleTypeId();
    protected abstract String sampleImportId();
    protected abstract String sampleTenancyId();

    private ImportCapable provider;

    @BeforeEach
    void setUp() {
        provider = createImportCapableProvider();
    }

    @Test
    void importResourceReturnsNonNull() {
        assertThat(provider.importResource(sampleTypeId(), sampleImportId(), sampleTenancyId()))
            .isNotNull();
    }

    @Test
    void importSuccessHasState() {
        var result = provider.importResource(sampleTypeId(), sampleImportId(), sampleTenancyId());
        if (result instanceof BridgeImportResult.Success s) {
            assertThat(s.state()).isNotNull();
        }
    }
}
