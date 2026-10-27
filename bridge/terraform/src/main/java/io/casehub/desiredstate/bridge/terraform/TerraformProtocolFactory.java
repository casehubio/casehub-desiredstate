package io.casehub.desiredstate.bridge.terraform;

import io.grpc.ManagedChannel;

public final class TerraformProtocolFactory {

    private TerraformProtocolFactory() {}

    public static TerraformProtocol create(GoPluginHandshake handshake, ManagedChannel channel) {
        return switch (handshake.appProtocol()) {
            case 5 -> new ProtocolV5Adapter(channel);
            case 6 -> new ProtocolV6Adapter(channel);
            default -> throw new IllegalArgumentException(
                "Unsupported Terraform plugin protocol version: " + handshake.appProtocol());
        };
    }
}
