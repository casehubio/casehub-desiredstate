package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DomainIdTest {
    @Test void equality() {
        assertThat(DomainId.of("infra")).isEqualTo(DomainId.of("infra"));
        assertThat(DomainId.of("infra")).isNotEqualTo(DomainId.of("deployment"));
    }
    @Test void rejectsNull() {
        assertThatThrownBy(() -> DomainId.of(null))
            .isInstanceOf(NullPointerException.class);
    }
    @Test void rejectsBlank() {
        assertThatThrownBy(() -> DomainId.of(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DomainId.of("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void value() {
        assertThat(DomainId.of("infra").value()).isEqualTo("infra");
    }
}
