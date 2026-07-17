package io.casehub.desiredstate.api;

public enum HumanGating {
    NONE,
    PROVISION_ONLY,
    DEPROVISION_ONLY,
    ALL;

    public boolean requiresHuman(StepAction action) {
        return switch (action) {
            case PROVISION -> this == PROVISION_ONLY || this == ALL;
            case DEPROVISION -> this == DEPROVISION_ONLY || this == ALL;
        };
    }

    public boolean any() {
        return this != NONE;
    }

    public HumanGating merge(HumanGating other) {
        if (this == ALL || other == ALL) return ALL;
        boolean p = requiresHuman(StepAction.PROVISION) || other.requiresHuman(StepAction.PROVISION);
        boolean d = requiresHuman(StepAction.DEPROVISION) || other.requiresHuman(StepAction.DEPROVISION);
        if (p && d) return ALL;
        if (p) return PROVISION_ONLY;
        if (d) return DEPROVISION_ONLY;
        return NONE;
    }
}
