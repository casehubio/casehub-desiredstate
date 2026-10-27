package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.bridge.*;
import java.util.*;

public class MockBridgeProvider implements BridgeProvider {

    private final String protocolId;
    private final Set<String> types;
    private BridgeResult applyResult = new BridgeResult.Success(Map.of());
    private BridgeResult destroyResult = new BridgeResult.Success(Map.of());
    private BridgeStateResult readResult = new BridgeStateResult.NotFound();
    private final List<BridgeRequest> applyCalls = new ArrayList<>();
    private final List<BridgeRequest> destroyCalls = new ArrayList<>();
    private final List<BridgeReadRequest> readCalls = new ArrayList<>();

    public MockBridgeProvider(String protocolId, String... types) {
        this.protocolId = protocolId;
        this.types = Set.of(types);
    }

    @Override public String protocolId() { return protocolId; }
    @Override public Set<String> supportedTypes() { return types; }

    @Override
    public BridgeResult apply(BridgeRequest request) {
        applyCalls.add(request);
        return applyResult;
    }

    @Override
    public BridgeResult destroy(BridgeRequest request) {
        destroyCalls.add(request);
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
    public List<BridgeRequest> applyCalls() { return applyCalls; }
    public List<BridgeRequest> destroyCalls() { return destroyCalls; }
    public List<BridgeReadRequest> readCalls() { return readCalls; }
}
