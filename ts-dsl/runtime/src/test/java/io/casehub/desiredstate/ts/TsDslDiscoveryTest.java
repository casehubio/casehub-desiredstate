package io.casehub.desiredstate.ts;

import io.casehub.desiredstate.api.BeanRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TsDslDiscoveryTest {

    @Test
    void discover_returnsEmptyForEmptyEnvelopeList() {
        List<BeanRegistration> beans = new TsDslDiscovery().discover(
            List.of(), Map.of());
        assertThat(beans).isEmpty();
    }
}
