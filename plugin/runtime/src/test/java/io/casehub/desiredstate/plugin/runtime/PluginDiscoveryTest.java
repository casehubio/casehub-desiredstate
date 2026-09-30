package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.BeanRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDiscoveryTest {

    @Test
    void discover_returnsEmptyForEmptyPluginList() {
        List<BeanRegistration> beans = new PluginDiscovery().discover(List.of());
        assertThat(beans).isEmpty();
    }
}
