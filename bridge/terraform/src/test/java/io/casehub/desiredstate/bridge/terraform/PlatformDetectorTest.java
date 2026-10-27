package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformDetectorTest {

    @Test
    void osReturnsKnownValue() {
        String os = PlatformDetector.os();
        assertThat(os).isIn("darwin", "linux", "windows");
    }

    @Test
    void archReturnsKnownValue() {
        String arch = PlatformDetector.arch();
        assertThat(arch).isIn("amd64", "arm64");
    }
}
