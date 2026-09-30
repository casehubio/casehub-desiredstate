package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.api.BeanRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlDiscoveryTest {

    @Test
    void discover_returnsEmptyForEmptyGraphList() {
        List<BeanRegistration> beans = new YamlDiscovery().discover(
            List.of(), Map.of(), Map.of());
        assertThat(beans).isEmpty();
    }
}
