package io.casehub.desiredstate.bridge.terraform;

import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class UnixSocketConnectionTest {

    @Test
    void connectToProviderViaUnixSocket() throws Exception {
        var registryClient = new ProviderRegistryClient();
        var config = new ProviderConfig("hashicorp", "null", "3.2.3", null);
        var binary = registryClient.resolve(config);

        ProcessBuilder pb = new ProcessBuilder(binary.binaryPath().toString());
        pb.environment().put("TF_PLUGIN_MAGIC_COOKIE",
            "d602bf8f470bc67ca7faa0386276bbdd4330efaf76d1a219cb4d6991ca9872b2");
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            assertThat(line).isNotNull();
            System.out.println("Handshake: " + line);

            var handshake = GoPluginHandshake.parse(line);
            System.out.println("Network: " + handshake.networkType() + " Address: " + handshake.address());

            ManagedChannel channel = NettyChannelBuilder
                .forAddress(new DomainSocketAddress(handshake.address()))
                .eventLoopGroup(new KQueueEventLoopGroup())
                .channelType(KQueueDomainSocketChannel.class)
                .usePlaintext()
                .build();

            try {
                var v5Stub = io.casehub.desiredstate.bridge.terraform.proto.v5.ProviderGrpc
                    .newBlockingStub(channel);
                var schemaResp = v5Stub.getSchema(
                    io.casehub.desiredstate.bridge.terraform.proto.v5.GetProviderSchema.Request
                        .newBuilder().build());
                System.out.println("Schema resources: " + schemaResp.getResourceSchemasMap().keySet());
                assertThat(schemaResp.getResourceSchemasMap()).containsKey("null_resource");
            } finally {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            process.destroyForcibly();
        }
    }
}
