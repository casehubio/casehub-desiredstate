package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.bridge.testing.BridgeProviderContractTest;
import java.util.Map;
import java.util.Set;

class MockCloudProviderContractTest extends BridgeProviderContractTest {

    @Override
    protected BridgeProvider createProvider() {
        return new MockCloudProvider();
    }

    @Override
    protected String expectedProtocolId() {
        return "mock-cloud";
    }

    @Override
    protected Set<String> expectedSupportedTypes() {
        return Set.of("cloud:vm", "cloud:network", "cloud:storage");
    }

    @Override
    protected BridgeRequest sampleApplyRequest() {
        return new BridgeRequest("cloud:vm", "vm-1",
            Map.of("instance_type", "t3.micro", "ami", "ami-abc123"),
            "tenant-1");
    }

    @Override
    protected BridgeRequest sampleDestroyRequest() {
        return new BridgeRequest("cloud:vm", "vm-1", Map.of(), "tenant-1");
    }

    @Override
    protected BridgeReadRequest sampleReadRequest() {
        return new BridgeReadRequest("cloud:vm", "vm-1", "tenant-1");
    }
}
