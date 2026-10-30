package io.casehub.desiredstate.bridge.testing;

import io.casehub.desiredstate.bridge.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MockBridgeProvider implements BridgeProvider {

    private final String protocolId;
    private final Set<String> types;
    private BridgeResult applyResult = new BridgeResult.Success(Map.of());
    private BridgeResult destroyResult = new BridgeResult.Success(Map.of());
    private BridgeStateResult readResult = new BridgeStateResult.NotFound();
    private final List<BridgeRequest> applyCalls = new ArrayList<>();
    private final List<BridgeRequest> destroyCalls = new ArrayList<>();
    private final List<BridgeReadRequest> readCalls = new ArrayList<>();

    private final AtomicInteger applyFailCount = new AtomicInteger(0);
    private BridgeResult applyFailResult;
    private final AtomicInteger destroyFailCount = new AtomicInteger(0);
    private BridgeResult destroyFailResult;

    public MockBridgeProvider(String protocolId, String... types) {
        this.protocolId = protocolId;
        this.types = Set.of(types);
    }

    @Override public String protocolId() { return protocolId; }
    @Override public Set<String> supportedTypes() { return types; }

    @Override
    public BridgeResult apply(BridgeRequest request) {
        applyCalls.add(request);
        if (applyFailCount.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            return applyFailResult;
        }
        return applyResult;
    }

    @Override
    public BridgeResult destroy(BridgeRequest request) {
        destroyCalls.add(request);
        if (destroyFailCount.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            return destroyFailResult;
        }
        return destroyResult;
    }

    @Override
    public BridgeStateResult readState(BridgeReadRequest request) {
        readCalls.add(request);
        return readResult;
    }

    public void setApplyResult(BridgeResult result) { this.applyResult = result; }
    public void setDestroyResult(BridgeResult result) { this.destroyResult = result; }
    public void setReadResult(BridgeStateResult result) { this.readResult = result; }

    public void failNextN(int n, BridgeResult failResult) {
        this.applyFailCount.set(n);
        this.applyFailResult = failResult;
    }

    public void failNextNDestroy(int n, BridgeResult failResult) {
        this.destroyFailCount.set(n);
        this.destroyFailResult = failResult;
    }

    public List<BridgeRequest> applyCalls() { return applyCalls; }
    public List<BridgeRequest> destroyCalls() { return destroyCalls; }
    public List<BridgeReadRequest> readCalls() { return readCalls; }
}
