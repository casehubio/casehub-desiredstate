package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import java.util.Map;

class MockSuspendCapableContractTest extends SuspendCapableContractTest {

    @Override
    protected SuspendCapable createSuspendCapableProvider() {
        return new MockSuspendCapableBridgeProvider("test-proto", "test_resource");
    }

    @Override
    protected BridgeRequest sampleSuspendRequest() {
        return new BridgeRequest("test_resource", "node-1", Map.of(), "tenant-1");
    }
}
