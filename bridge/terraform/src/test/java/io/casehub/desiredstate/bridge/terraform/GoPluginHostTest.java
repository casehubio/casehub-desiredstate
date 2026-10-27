package io.casehub.desiredstate.bridge.terraform;

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
}
