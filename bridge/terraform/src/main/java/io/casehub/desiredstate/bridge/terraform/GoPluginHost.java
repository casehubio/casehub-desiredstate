package io.casehub.desiredstate.bridge.terraform;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GoPluginHost implements AutoCloseable {

    private static final Logger log = Logger.getLogger(GoPluginHost.class.getName());

    private Process process;
    private ManagedChannel channel;
    private GoPluginHandshake handshake;
    private EventLoopGroup eventLoopGroup;
    private boolean ownsEventLoopGroup;
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    public ManagedChannel connect(String binaryPath, List<String> args) {
        return connect(binaryPath, args, null);
    }

    public ManagedChannel connect(String binaryPath, List<String> args, EventLoopGroup sharedGroup) {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Provider already running");
        }

        List<String> command = new ArrayList<>();
        command.add(binaryPath);
        command.addAll(args);

        try {
            CertificateBundle clientBundle = PluginCertificateGenerator.generate();

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put(GoPluginHandshake.MAGIC_COOKIE_KEY,
                                 GoPluginHandshake.MAGIC_COOKIE_VALUE);
            pb.environment().put("PLUGIN_MIN_PORT", "10000");
            pb.environment().put("PLUGIN_MAX_PORT", "25000");
            pb.environment().put("PLUGIN_CLIENT_CERT", clientBundle.certPem());
            pb.redirectErrorStream(false);
            process = pb.start();

            BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream()));

            String handshakeLine = readHandshakeLine(stdout);
            handshake = GoPluginHandshake.parse(handshakeLine);

            SslContext sslContext = null;
            if (handshake.hasTls()) {
                sslContext = buildSslContext(clientBundle, handshake.serverCertDer());
            }

            if (sharedGroup != null) {
                this.eventLoopGroup = sharedGroup;
                this.ownsEventLoopGroup = false;
            }

            channel = buildChannel(handshake, sslContext);

            return channel;
        } catch (Exception e) {
            shutdown();
            throw new RuntimeException("Failed to start provider: " + binaryPath, e);
        }
    }

    public GoPluginHandshake handshake() {
        return handshake;
    }

    public ManagedChannel channel() {
        return channel;
    }

    public boolean isHealthy() {
        return process != null && process.isAlive()
            && channel != null && !channel.isShutdown();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
            channel = null;
        }
        if (eventLoopGroup != null && ownsEventLoopGroup) {
            eventLoopGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        eventLoopGroup = null;
        ownsEventLoopGroup = false;
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        handshake = null;
    }

    private SslContext buildSslContext(CertificateBundle clientBundle, byte[] serverCertDer) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate serverCert = (X509Certificate) cf.generateCertificate(
                new ByteArrayInputStream(serverCertDer));

            return SslContextBuilder.forClient()
                .keyManager(clientBundle.privateKey(), clientBundle.certificate())
                .trustManager(serverCert)
                .sslProvider(SslProvider.JDK)
                .build();
        } catch (Exception e) {
            log.log(Level.WARNING,
                "Failed to build TLS context from server certificate, falling back to plaintext", e);
            return null;
        }
    }

    private ManagedChannel buildChannel(GoPluginHandshake hs, SslContext sslContext) {
        if ("unix".equals(hs.networkType())) {
            String osName = System.getProperty("os.name").toLowerCase();
            Class<? extends io.netty.channel.Channel> channelType;
            if (eventLoopGroup == null) {
                if (osName.contains("mac") || osName.contains("darwin")) {
                    eventLoopGroup = new KQueueEventLoopGroup();
                } else {
                    eventLoopGroup = new EpollEventLoopGroup();
                }
                ownsEventLoopGroup = true;
            }
            if (osName.contains("mac") || osName.contains("darwin")) {
                channelType = KQueueDomainSocketChannel.class;
            } else {
                channelType = EpollDomainSocketChannel.class;
            }
            NettyChannelBuilder builder = NettyChannelBuilder
                .forAddress(new DomainSocketAddress(hs.address()))
                .eventLoopGroup(eventLoopGroup)
                .channelType(channelType);
            if (sslContext != null) {
                builder.sslContext(sslContext).overrideAuthority("localhost");
            } else {
                builder.usePlaintext();
            }
            return builder.build();
        }
        if (sslContext != null) {
            return NettyChannelBuilder.forTarget(hs.address())
                .sslContext(sslContext)
                .overrideAuthority("localhost")
                .build();
        }
        return ManagedChannelBuilder.forTarget(hs.address())
            .usePlaintext()
            .build();
    }

    private String readHandshakeLine(BufferedReader reader) {
        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT.toMillis();
        try {
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && !line.isEmpty()) {
                        return line;
                    }
                }
                if (!process.isAlive()) {
                    throw new RuntimeException(
                        "Provider process exited with code " + process.exitValue()
                        + " before completing handshake");
                }
                Thread.sleep(50);
            }
            throw new RuntimeException("Timed out waiting for go-plugin handshake");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read handshake", e);
        }
    }
}
