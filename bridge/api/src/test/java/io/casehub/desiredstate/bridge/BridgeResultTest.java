package io.casehub.desiredstate.bridge;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BridgeResultTest {

    @Test
    void successCarriesOutputState() {
        var state = Map.of("id", (Object) "i-123", "status", "running");
        var result = new BridgeResult.Success(state);
        assertEquals("i-123", result.outputState().get("id"));
    }

    @Test
    void failedCarriesReasonAndCategory() {
        var result = new BridgeResult.Failed("bad AMI", BridgeErrorCategory.CONFIGURATION);
        assertEquals("bad AMI", result.reason());
        assertEquals(BridgeErrorCategory.CONFIGURATION, result.category());
    }

    @Test
    void pendingApprovalCarriesPlanReference() {
        var result = new BridgeResult.PendingApproval("plan-42");
        assertEquals("plan-42", result.planReference());
    }

    @Test
    void stateResultFoundCarriesState() {
        var state = Map.of("status", (Object) "running");
        var result = new BridgeStateResult.Found(state);
        assertEquals("running", result.state().get("status"));
    }

    @Test
    void stateResultNotFoundIsDistinct() {
        var result = new BridgeStateResult.NotFound();
        assertInstanceOf(BridgeStateResult.class, result);
    }

    @Test
    void stateResultErrorCarriesReason() {
        var result = new BridgeStateResult.Error("timeout");
        assertEquals("timeout", result.reason());
    }

    @Test
    void sealedExhaustiveness() {
        BridgeResult result = new BridgeResult.Success(Map.of());
        var label = switch (result) {
            case BridgeResult.Success s -> "success";
            case BridgeResult.Failed f -> "failed";
            case BridgeResult.PendingApproval p -> "pending";
        };
        assertEquals("success", label);
    }
}
