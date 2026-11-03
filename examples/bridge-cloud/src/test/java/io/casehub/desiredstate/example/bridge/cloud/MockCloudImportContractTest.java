package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.bridge.testing.ImportCapableContractTest;
import java.util.Map;

class MockCloudImportContractTest extends ImportCapableContractTest {

    private final MockCloudProvider provider = new MockCloudProvider();

    @Override
    protected ImportCapable createImportCapableProvider() {
        provider.seedResource("cloud:vm", "i-existing",
            Map.of("status", "running", "instance_type", "t3.micro"));
        return provider;
    }

    @Override protected String sampleTypeId() { return "cloud:vm"; }
    @Override protected String sampleImportId() { return "i-existing"; }
    @Override protected String sampleTenancyId() { return "tenant-1"; }
}
