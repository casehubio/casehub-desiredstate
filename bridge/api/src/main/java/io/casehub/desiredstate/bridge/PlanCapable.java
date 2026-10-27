package io.casehub.desiredstate.bridge;

public interface PlanCapable {
    BridgePlan plan(BridgeRequest request);
    BridgeResult applyPlan(BridgePlan plan);
}
