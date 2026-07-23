package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultEventValidationTest {

    @Test
    void nullDetail_throwsNPE() {
        assertThatThrownBy(() -> new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("detail");
    }

    @Test
    void validConstruction_succeeds() {
        var event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "reason");
        assertThat(event.detail()).isEqualTo("reason");
    }
}
