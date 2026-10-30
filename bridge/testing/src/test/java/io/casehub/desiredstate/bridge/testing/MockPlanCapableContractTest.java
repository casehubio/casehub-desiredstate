package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import java.util.Map;

class MockPlanCapableContractTest extends PlanCapableContractTest {

    @Override
    protected PlanCapable createPlanCapableProvider() {
        return new MockPlanCapableBridgeProvider("test-proto", "test_resource");
    }

    @Override
    protected BridgeRequest samplePlanRequest() {
        return new BridgeRequest("test_resource", "node-1", Map.of("name", "test"), "tenant-1");
    }
}
