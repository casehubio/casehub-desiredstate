package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.bridge.*;
import java.util.*;

public class MockImportCapableBridgeProvider extends MockBridgeProvider implements ImportCapable {

    private BridgeImportResult importResult = new BridgeImportResult.Success(Map.of("id", "imported-123"));
    private final List<ImportCall> importCalls = new ArrayList<>();

    public MockImportCapableBridgeProvider(String protocolId, String... types) {
        super(protocolId, types);
    }

    @Override
    public BridgeImportResult importResource(String typeId, String importId, String tenancyId) {
        importCalls.add(new ImportCall(typeId, importId, tenancyId));
        return importResult;
    }

    public void setImportResult(BridgeImportResult result) { this.importResult = result; }
    public List<ImportCall> importCalls() { return importCalls; }

    public record ImportCall(String typeId, String importId, String tenancyId) {}
}
