package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.ImportCapable;

class MockImportCapableContractTest extends ImportCapableContractTest {

    @Override
    protected ImportCapable createImportCapableProvider() {
        return new MockImportCapableBridgeProvider("test-proto", "test_resource");
    }

    @Override
    protected String sampleTypeId() { return "test_resource"; }

    @Override
    protected String sampleImportId() { return "import-abc-123"; }

    @Override
    protected String sampleTenancyId() { return "tenant-1"; }
}
