package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoPluginHandshakeTest {

    @Test
    void parseValidV5Handshake() {
        var hs = GoPluginHandshake.parse("1|5|tcp|127.0.0.1:50051|grpc");
        assertThat(hs.coreProtocol()).isEqualTo(1);
        assertThat(hs.appProtocol()).isEqualTo(5);
        assertThat(hs.networkType()).isEqualTo("tcp");
        assertThat(hs.address()).isEqualTo("127.0.0.1:50051");
        assertThat(hs.protocol()).isEqualTo("grpc");
    }

    @Test
    void parseValidV6Handshake() {
        var hs = GoPluginHandshake.parse("1|6|tcp|127.0.0.1:50052|grpc");
        assertThat(hs.appProtocol()).isEqualTo(6);
    }

    @Test
    void parseHandshakeWithExtraFields() {
        var hs = GoPluginHandshake.parse("1|5|tcp|127.0.0.1:50051|grpc|extra|fields");
        assertThat(hs.appProtocol()).isEqualTo(5);
        assertThat(hs.address()).isEqualTo("127.0.0.1:50051");
    }

    @Test
    void parseHandshakeTrimsWhitespace() {
        var hs = GoPluginHandshake.parse("  1|5|tcp|127.0.0.1:50051|grpc  \n");
        assertThat(hs.appProtocol()).isEqualTo(5);
    }

    @Test
    void rejectNetrpcProtocol() {
        assertThatThrownBy(() -> GoPluginHandshake.parse("1|5|tcp|127.0.0.1:50051|netrpc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("netrpc");
    }

    @Test
    void rejectMalformedLine() {
        assertThatThrownBy(() -> GoPluginHandshake.parse("garbage"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("5+ pipe-separated");
    }

    @Test
    void rejectEmptyLine() {
        assertThatThrownBy(() -> GoPluginHandshake.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectNullLine() {
        assertThatThrownBy(() -> GoPluginHandshake.parse(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectTooFewFields() {
        assertThatThrownBy(() -> GoPluginHandshake.parse("1|5|tcp|127.0.0.1:50051"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("5+ pipe-separated");
    }

    @Test
    void magicCookieConstants() {
        assertThat(GoPluginHandshake.MAGIC_COOKIE_KEY).isEqualTo("TF_PLUGIN_MAGIC_COOKIE");
        assertThat(GoPluginHandshake.MAGIC_COOKIE_VALUE).isNotEmpty();
    }
}
