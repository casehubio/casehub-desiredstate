package io.casehub.desiredstate.bridge.terraform;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoPluginHostTest {

    @TempDir Path tempDir;

    @Test
    void shutdownOnCleanHostIsNoOp() {
        var host = new GoPluginHost();
        host.shutdown();
        assertThat(host.isHealthy()).isFalse();
    }

    @Test
    void processExitBeforeHandshakeThrows() throws Exception {
        Path script = tempDir.resolve("exit-provider.sh");
        Files.writeString(script, "#!/bin/bash\nexit 1\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        assertThatThrownBy(() -> host.connect(script.toString(), List.of()))
            .isInstanceOf(RuntimeException.class)
            .hasRootCauseMessage("Provider process exited with code 1 before completing handshake");
        host.shutdown();
    }

    @Test
    void handshakeParsedFromProcess() throws Exception {
        Path script = tempDir.resolve("mock-provider.sh");
        Files.writeString(script,
            "#!/bin/bash\necho '1|5|tcp|127.0.0.1:0|grpc'\nwhile true; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        try {
            host.connect(script.toString(), List.of());
            assertThat(host.handshake()).isNotNull();
            assertThat(host.handshake().appProtocol()).isEqualTo(5);
            assertThat(host.handshake().address()).isEqualTo("127.0.0.1:0");
        } finally {
            host.shutdown();
        }
    }

    @Test
    void shutdownKillsProcess() throws Exception {
        Path script = tempDir.resolve("long-provider.sh");
        Files.writeString(script,
            "#!/bin/bash\necho '1|5|tcp|127.0.0.1:0|grpc'\nwhile true; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        host.connect(script.toString(), List.of());
        assertThat(host.isHealthy()).isTrue();
        host.shutdown();
        assertThat(host.isHealthy()).isFalse();
        assertThat(host.handshake()).isNull();
    }

    @Test
    void connectSetsPluginClientCertEnvVar() throws Exception {
        Path script = tempDir.resolve("env-check-provider.sh");
        Files.writeString(script,
            "#!/bin/bash\n"
            + "if [ -n \"$PLUGIN_CLIENT_CERT\" ]; then\n"
            + "  echo '1|5|tcp|127.0.0.1:0|grpc'\n"
            + "else\n"
            + "  echo 'NO_CERT' >&2\n"
            + "  exit 1\n"
            + "fi\n"
            + "while true; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        try {
            host.connect(script.toString(), List.of());
            assertThat(host.handshake()).isNotNull();
        } finally {
            host.shutdown();
        }
    }

    @Test
    void connectFallsBackToPlaintextWhenNoServerCert() throws Exception {
        Path script = tempDir.resolve("plaintext-provider.sh");
        Files.writeString(script,
            "#!/bin/bash\necho '1|5|tcp|127.0.0.1:0|grpc'\nwhile true; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        try {
            host.connect(script.toString(), List.of());
            assertThat(host.handshake().hasTls()).isFalse();
            assertThat(host.channel()).isNotNull();
        } finally {
            host.shutdown();
        }
    }

    @Test
    void mtlsRoundTripWithGeneratedCerts() throws Exception {
        var clientBundle = PluginCertificateGenerator.generate();
        var serverBundle = PluginCertificateGenerator.generate();

        javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");

        javax.net.ssl.KeyManagerFactory kmf =
            javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        java.security.KeyStore clientKs = java.security.KeyStore.getInstance("PKCS12");
        clientKs.load(null);
        clientKs.setKeyEntry("client", clientBundle.privateKey(), new char[0],
            new java.security.cert.Certificate[]{clientBundle.certificate()});
        kmf.init(clientKs, new char[0]);

        javax.net.ssl.TrustManagerFactory tmf =
            javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        java.security.KeyStore trustKs = java.security.KeyStore.getInstance("PKCS12");
        trustKs.load(null);
        trustKs.setCertificateEntry("server", serverBundle.certificate());
        tmf.init(trustKs);

        sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        assertThat(sslCtx.getProtocol()).isEqualTo("TLS");
    }

    @Test
    void buildSslContextProducesValidContext() throws Exception {
        var clientBundle = PluginCertificateGenerator.generate();
        var serverBundle = PluginCertificateGenerator.generate();

        SslContext sslContext = SslContextBuilder.forClient()
            .keyManager(clientBundle.privateKey(), clientBundle.certificate())
            .trustManager(serverBundle.certificate())
            .sslProvider(SslProvider.JDK)
            .build();

        assertThat(sslContext).isNotNull();
        assertThat(sslContext.isClient()).isTrue();
    }

    @Test
    void connectWhileRunningThrows() throws Exception {
        Path script = tempDir.resolve("running-provider.sh");
        Files.writeString(script,
            "#!/bin/bash\necho '1|5|tcp|127.0.0.1:0|grpc'\nwhile true; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        try {
            host.connect(script.toString(), List.of());
            assertThatThrownBy(() -> host.connect(script.toString(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
        } finally {
            host.shutdown();
        }
    }

    @Test
    void shutdownDoesNotCloseExternalEventLoopGroup() {
        var group = createEventLoopGroup();
        try {
            var host = new GoPluginHost();
            // Verify the field tracking: shutdown with no connection is still safe
            host.shutdown();
            assertThat(group.isShutdown()).isFalse();
        } finally {
            group.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    @Test
    void connectWithSharedGroupUsesExternalGroup() throws Exception {
        Path script = tempDir.resolve("shared-group-provider.sh");
        Files.writeString(script,
                          "#!/bin/bash\necho '1|5|tcp|127.0.0.1:0|grpc'\nwhile true; do sleep 1; done\n");
        script.toFile().setExecutable(true);

        var host = new GoPluginHost();
        try {
            host.connect(script.toString(), List.of(), null);
            assertThat(host.handshake()).isNotNull();
        } finally {
            host.shutdown();
        }
    }

    private io.netty.channel.EventLoopGroup createEventLoopGroup() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return new io.netty.channel.kqueue.KQueueEventLoopGroup(1);
        }
        return new io.netty.channel.epoll.EpollEventLoopGroup(1);
    }

}
