package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import java.util.Map;
import java.util.Set;

class MockBridgeProviderContractTest extends BridgeProviderContractTest {

    @Override
    protected BridgeProvider createProvider() {
        var mock = new MockBridgeProvider("test-proto", "test_resource");
        mock.setApplyResult(new BridgeResult.Success(Map.of("id", "created-1")));
        mock.setReadResult(new BridgeStateResult.Found(Map.of("status", "active")));
        return mock;
    }

    @Override
    protected String expectedProtocolId() { return "test-proto"; }

    @Override
    protected Set<String> expectedSupportedTypes() { return Set.of("test_resource"); }

    @Override
    protected BridgeRequest sampleApplyRequest() {
        return new BridgeRequest("test_resource", "node-1", Map.of("name", "test"), "tenant-1");
    }

    @Override
    protected BridgeRequest sampleDestroyRequest() {
        return new BridgeRequest("test_resource", "node-1", Map.of(), "tenant-1");
    }

    @Override
    protected BridgeReadRequest sampleReadRequest() {
        return new BridgeReadRequest("test_resource", "node-1", "tenant-1");
    }
}
