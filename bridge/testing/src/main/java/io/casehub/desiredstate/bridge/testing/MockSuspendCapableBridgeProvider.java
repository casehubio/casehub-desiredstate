package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import java.util.*;

public class MockSuspendCapableBridgeProvider extends MockBridgeProvider implements SuspendCapable {

    private BridgeResult suspendResult = new BridgeResult.Success(Map.of());
    private BridgeResult resumeResult = new BridgeResult.Success(Map.of());
    private final List<BridgeRequest> suspendCalls = new ArrayList<>();
    private final List<BridgeRequest> resumeCalls = new ArrayList<>();

    public MockSuspendCapableBridgeProvider(String protocolId, String... types) {
        super(protocolId, types);
    }

    @Override
    public BridgeResult suspend(BridgeRequest request) {
        suspendCalls.add(request);
        return suspendResult;
    }

    @Override
    public BridgeResult resume(BridgeRequest request) {
        resumeCalls.add(request);
        return resumeResult;
    }

    public void setSuspendResult(BridgeResult result) { this.suspendResult = result; }
    public void setResumeResult(BridgeResult result) { this.resumeResult = result; }
    public List<BridgeRequest> suspendCalls() { return suspendCalls; }
    public List<BridgeRequest> resumeCalls() { return resumeCalls; }
}
