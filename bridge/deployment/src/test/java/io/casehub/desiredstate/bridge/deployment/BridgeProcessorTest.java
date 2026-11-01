package io.casehub.desiredstate.bridge.deployment;

import io.casehub.desiredstate.bridge.BridgeDescriptor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeProcessorTest {

    @Test
    void discoverManifestsFindsYamlOnClasspath() throws IOException {
        List<BridgeDescriptor> descriptors = BridgeProcessor.discoverManifests();

        assertThat(descriptors).isNotEmpty();
        assertThat(descriptors.get(0).protocolId()).isEqualTo("test-provider");
        assertThat(descriptors.get(0).handledTypes()).isNotEmpty();
    }
}
