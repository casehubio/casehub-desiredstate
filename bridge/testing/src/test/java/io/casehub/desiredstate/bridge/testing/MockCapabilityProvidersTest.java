package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MockCapabilityProvidersTest {

    @Test
    void planCapableReturnsPlanAndRecordsCalls() {
        var mock = new MockPlanCapableBridgeProvider("proto", "res");
        var plan = new BridgePlan("p1", "create resource", List.of(), null);
        mock.setPlanResult(plan);
        mock.setApplyPlanResult(new BridgeResult.Success(Map.of("id", "r1")));

        var req = new BridgeRequest("res", "node-1", Map.of(), "t1");
        var planResult = mock.plan(req);
        var applyResult = mock.applyPlan(planResult);

        assertThat(planResult.planId()).isEqualTo("p1");
        assertThat(applyResult).isInstanceOf(BridgeResult.Success.class);
        assertThat(mock.planCalls()).hasSize(1);
        assertThat(mock.applyPlanCalls()).hasSize(1);
    }

    @Test
    void suspendCapableRecordsSuspendAndResumeCalls() {
        var mock = new MockSuspendCapableBridgeProvider("proto", "res");
        mock.setSuspendResult(new BridgeResult.Success(Map.of()));
        mock.setResumeResult(new BridgeResult.Success(Map.of()));

        var req = new BridgeRequest("res", "node-1", Map.of(), "t1");
        assertThat(mock.suspend(req)).isInstanceOf(BridgeResult.Success.class);
        assertThat(mock.resume(req)).isInstanceOf(BridgeResult.Success.class);
        assertThat(mock.suspendCalls()).hasSize(1);
        assertThat(mock.resumeCalls()).hasSize(1);
    }
}
