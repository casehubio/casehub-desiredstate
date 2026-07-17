package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HumanGatingTest {

    @Test void none_requiresHuman_bothFalse() {
        assertThat(HumanGating.NONE.requiresHuman(StepAction.PROVISION)).isFalse();
        assertThat(HumanGating.NONE.requiresHuman(StepAction.DEPROVISION)).isFalse();
    }

    @Test void none_any_false() {
        assertThat(HumanGating.NONE.any()).isFalse();
    }

    @Test void provisionOnly_requiresHuman_provisionTrue_deprovisionFalse() {
        assertThat(HumanGating.PROVISION_ONLY.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(HumanGating.PROVISION_ONLY.requiresHuman(StepAction.DEPROVISION)).isFalse();
    }

    @Test void provisionOnly_any_true() {
        assertThat(HumanGating.PROVISION_ONLY.any()).isTrue();
    }

    @Test void deprovisionOnly_requiresHuman_provisionFalse_deprovisionTrue() {
        assertThat(HumanGating.DEPROVISION_ONLY.requiresHuman(StepAction.PROVISION)).isFalse();
        assertThat(HumanGating.DEPROVISION_ONLY.requiresHuman(StepAction.DEPROVISION)).isTrue();
    }

    @Test void deprovisionOnly_any_true() {
        assertThat(HumanGating.DEPROVISION_ONLY.any()).isTrue();
    }

    @Test void all_requiresHuman_bothTrue() {
        assertThat(HumanGating.ALL.requiresHuman(StepAction.PROVISION)).isTrue();
        assertThat(HumanGating.ALL.requiresHuman(StepAction.DEPROVISION)).isTrue();
    }

    @Test void all_any_true() {
        assertThat(HumanGating.ALL.any()).isTrue();
    }

    @Test void merge_none_none_isNone() {
        assertThat(HumanGating.NONE.merge(HumanGating.NONE)).isEqualTo(HumanGating.NONE);
    }

    @Test void merge_none_all_isAll() {
        assertThat(HumanGating.NONE.merge(HumanGating.ALL)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_all_none_isAll() {
        assertThat(HumanGating.ALL.merge(HumanGating.NONE)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_provisionOnly_deprovisionOnly_isAll() {
        assertThat(HumanGating.PROVISION_ONLY.merge(HumanGating.DEPROVISION_ONLY)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_deprovisionOnly_provisionOnly_isAll() {
        assertThat(HumanGating.DEPROVISION_ONLY.merge(HumanGating.PROVISION_ONLY)).isEqualTo(HumanGating.ALL);
    }

    @Test void merge_provisionOnly_provisionOnly_isProvisionOnly() {
        assertThat(HumanGating.PROVISION_ONLY.merge(HumanGating.PROVISION_ONLY)).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test void merge_deprovisionOnly_deprovisionOnly_isDeprovisionOnly() {
        assertThat(HumanGating.DEPROVISION_ONLY.merge(HumanGating.DEPROVISION_ONLY)).isEqualTo(HumanGating.DEPROVISION_ONLY);
    }

    @Test void merge_provisionOnly_none_isProvisionOnly() {
        assertThat(HumanGating.PROVISION_ONLY.merge(HumanGating.NONE)).isEqualTo(HumanGating.PROVISION_ONLY);
    }

    @Test void merge_none_deprovisionOnly_isDeprovisionOnly() {
        assertThat(HumanGating.NONE.merge(HumanGating.DEPROVISION_ONLY)).isEqualTo(HumanGating.DEPROVISION_ONLY);
    }

    @Test void merge_all_withEveryValue_isAll() {
        for (HumanGating other : HumanGating.values()) {
            assertThat(HumanGating.ALL.merge(other)).isEqualTo(HumanGating.ALL);
            assertThat(other.merge(HumanGating.ALL)).isEqualTo(HumanGating.ALL);
        }
    }
}
