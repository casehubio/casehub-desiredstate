package io.casehub.desiredstate.bridge.terraform;

import io.grpc.ManagedChannel;

import java.nio.file.Path;
import java.util.List;

public class ProviderProcessManager implements AutoCloseable {

    private final GoPluginHost host;
    private final ProviderConfig config;
    private final Path binaryPath;
    private TerraformProtocol protocol;
    private List<DiscoveredType> discoveredTypes;
    private final Object lock = new Object();

    public ProviderProcessManager(GoPluginHost host, ProviderConfig config, Path binaryPath) {
        this.host = host;
        this.config = config;
        this.binaryPath = binaryPath;
    }

    public TerraformProtocol getProtocol() {
        synchronized (lock) {
            if (protocol == null || !host.isHealthy()) {
                start();
            }
            return protocol;
        }
    }

    public List<DiscoveredType> discoverTypes() {
        synchronized (lock) {
            if (discoveredTypes == null) {
                var proto = getProtocol();
                var schema = proto.getSchema();
                discoveredTypes = SchemaTranslator.translate(
                    config.namespace(), config.type(), schema);
            }
            return discoveredTypes;
        }
    }

    public ProviderConfig config() {
        return config;
    }

    public void shutdown() {
        synchronized (lock) {
            if (protocol != null) {
                try {
                    protocol.close();
                } catch (Exception ignored) {}
                protocol = null;
            }
            host.shutdown();
            discoveredTypes = null;
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void start() {
        if (protocol != null) {
            try { protocol.close(); } catch (Exception ignored) {}
            protocol = null;
        }
        host.shutdown();

        ManagedChannel channel = host.connect(binaryPath.toString(), List.of());
        protocol = TerraformProtocolFactory.create(host.handshake(), channel);
    }
}
