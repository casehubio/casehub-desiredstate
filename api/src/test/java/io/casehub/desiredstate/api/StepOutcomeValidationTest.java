package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepOutcomeValidationTest {

    @Test
    void failed_nullReason_throwsNPE() {
        assertThatThrownBy(() -> new StepOutcome.Failed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void rejected_nullReason_throwsNPE() {
        assertThatThrownBy(() -> new StepOutcome.Rejected(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void skipped_nullReason_throwsNPE() {
        assertThatThrownBy(() -> new StepOutcome.Skipped(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reason");
    }
}
