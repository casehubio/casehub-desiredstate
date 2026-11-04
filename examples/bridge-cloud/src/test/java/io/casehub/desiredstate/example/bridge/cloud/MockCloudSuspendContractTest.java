package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.bridge.testing.SuspendCapableContractTest;
import java.util.Map;

class MockCloudSuspendContractTest extends SuspendCapableContractTest {

    private final MockCloudProvider provider = new MockCloudProvider();

    @Override
    protected SuspendCapable createSuspendCapableProvider() {
        provider.seedResource("cloud:vm", "vm-1",
            Map.of("status", "running", "instance_type", "t3.micro"));
        return provider;
    }

    @Override
    protected BridgeRequest sampleSuspendRequest() {
        return new BridgeRequest("cloud:vm", "vm-1",
            Map.of("instance_type", "t3.micro"), "tenant-1");
    }
}
