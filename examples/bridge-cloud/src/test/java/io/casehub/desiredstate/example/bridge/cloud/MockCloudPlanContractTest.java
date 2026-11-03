package io.casehub.desiredstate.example.bridge.cloud;

import io.casehub.desiredstate.bridge.*;
import io.casehub.desiredstate.bridge.testing.PlanCapableContractTest;
import java.util.Map;

class MockCloudPlanContractTest extends PlanCapableContractTest {

    @Override
    protected PlanCapable createPlanCapableProvider() {
        return new MockCloudProvider();
    }

    @Override
    protected BridgeRequest samplePlanRequest() {
        return new BridgeRequest("cloud:vm", "vm-1",
            Map.of("instance_type", "t3.micro"),
            "tenant-1");
    }
}
