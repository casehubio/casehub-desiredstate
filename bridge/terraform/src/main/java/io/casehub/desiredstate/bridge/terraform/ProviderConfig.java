package io.casehub.desiredstate.bridge.terraform;

public record ProviderConfig(
    String namespace,
    String type,
    String version,
    String binaryPath
) {
    public String providerAddress() {
        return namespace + "/" + type;
    }
}
