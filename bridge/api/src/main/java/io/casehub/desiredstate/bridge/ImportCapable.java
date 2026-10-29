package io.casehub.desiredstate.bridge;

public interface ImportCapable {
    BridgeImportResult importResource(String typeId, String importId, String tenancyId);
}
