package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import java.util.*;

public class MockPlanCapableBridgeProvider extends MockBridgeProvider implements PlanCapable {

    private BridgePlan planResult = new BridgePlan("default-plan", "no-op", List.of(), null);
    private BridgeResult applyPlanResult = new BridgeResult.Success(Map.of());
    private final List<BridgeRequest> planCalls = new ArrayList<>();
    private final List<BridgePlan> applyPlanCalls = new ArrayList<>();

    public MockPlanCapableBridgeProvider(String protocolId, String... types) {
        super(protocolId, types);
    }

    @Override
    public BridgePlan plan(BridgeRequest request) {
        planCalls.add(request);
        return planResult;
    }

    @Override
    public BridgeResult applyPlan(BridgePlan plan) {
        applyPlanCalls.add(plan);
        return applyPlanResult;
    }

    public void setPlanResult(BridgePlan result) { this.planResult = result; }
    public void setApplyPlanResult(BridgeResult result) { this.applyPlanResult = result; }
    public List<BridgeRequest> planCalls() { return planCalls; }
    public List<BridgePlan> applyPlanCalls() { return applyPlanCalls; }
}
