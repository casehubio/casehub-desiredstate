package io.casehub.desiredstate.plugin.testing.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InfrastructureFactoryTest {

    @Test
    void createsHttpMockInfrastructure() {
        var infra = InfrastructureFactory.create("http-mock");
        assertThat(infra).isInstanceOf(HttpMockInfrastructure.class);
    }

    @Test
    void createsShellSandboxInfrastructure() {
        var infra = InfrastructureFactory.create("shell-sandbox");
        assertThat(infra).isInstanceOf(ShellSandboxInfrastructure.class);
    }

    @Test
    void unknownTypeThrows() {
        assertThatThrownBy(() -> InfrastructureFactory.create("k8s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("k8s");
    }
}
