package io.casehub.desiredstate.bridge.terraform;

import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.SuspendCapable;
import io.casehub.desiredstate.bridge.testing.SuspendCapableContractTest;

import java.util.List;
import java.util.Map;

class TerraformSuspendCapableContractTest extends SuspendCapableContractTest {

    @Override
    protected SuspendCapable createSuspendCapableProvider() {
        var mockProtocol = new TerraformBridgeProviderTest.MockTerraformProtocol();
        mockProtocol.planResponse = new PlanResponse(
            Map.of("id", "i-123", "instance_state", "stopped"),
            new byte[]{1}, false, List.of());
        mockProtocol.applyResponse = new ApplyResponse(
            Map.of("id", "i-123", "instance_state", "stopped"), new byte[0], List.of());

        var registry = new TerraformBridgeProviderTest.MockTenantProviderRegistry(mockProtocol);
        return new TerraformBridgeProvider(
            registry,
            Map.of("tf:mock/test:mock_resource",
                new TerraformSuspendConfig("instance_state", "stopped", "running")));
    }

    @Override
    protected BridgeRequest sampleSuspendRequest() {
        return new BridgeRequest("tf:mock/test:mock_resource", "web-1",
            Map.of("ami", "ami-123"), "tenant-1",
            Map.of("id", "i-123", "instance_state", "running"));
    }
}
