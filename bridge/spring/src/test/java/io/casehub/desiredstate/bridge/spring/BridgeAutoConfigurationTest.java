package io.casehub.desiredstate.bridge.spring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeAutoConfigurationTest {

    @Test
    void discoverManifestsFindsYamlFiles() {
        var descriptors = BridgeAutoConfiguration.discoverManifests(
            Thread.currentThread().getContextClassLoader());

        assertThat(descriptors).isNotEmpty();
        assertThat(descriptors.get(0).protocolId()).isEqualTo("test-provider");
    }
}
