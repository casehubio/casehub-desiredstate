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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GoPluginHost implements AutoCloseable {

    private Process process;
    private ManagedChannel channel;
    private GoPluginHandshake handshake;
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    public ManagedChannel connect(String binaryPath, List<String> args) {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Provider already running");
        }

        List<String> command = new ArrayList<>();
        command.add(binaryPath);
        command.addAll(args);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put(GoPluginHandshake.MAGIC_COOKIE_KEY,
                                 GoPluginHandshake.MAGIC_COOKIE_VALUE);
            pb.environment().put("PLUGIN_MIN_PORT", "10000");
            pb.environment().put("PLUGIN_MAX_PORT", "25000");
            pb.redirectErrorStream(false);
            process = pb.start();

            BufferedReader stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream()));

            String handshakeLine = readHandshakeLine(stdout);
            handshake = GoPluginHandshake.parse(handshakeLine);

            channel = buildChannel(handshake);

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
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        handshake = null;
    }

    private ManagedChannel buildChannel(GoPluginHandshake hs) {
        if ("unix".equals(hs.networkType())) {
            String osName = System.getProperty("os.name").toLowerCase();
            EventLoopGroup group;
            Class<? extends io.netty.channel.Channel> channelType;
            if (osName.contains("mac") || osName.contains("darwin")) {
                group = new KQueueEventLoopGroup();
                channelType = KQueueDomainSocketChannel.class;
            } else {
                group = new EpollEventLoopGroup();
                channelType = EpollDomainSocketChannel.class;
            }
            return NettyChannelBuilder.forAddress(new DomainSocketAddress(hs.address()))
                .eventLoopGroup(group)
                .channelType(channelType)
                .usePlaintext()
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
