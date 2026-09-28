package io.casehub.desiredstate.api;

public enum HumanGating {
    NONE,
    PROVISION_ONLY,
    DEPROVISION_ONLY,
    SUSPEND_ONLY,
    RESUME_ONLY,
    ALL;

    public boolean requiresHuman(StepAction action) {
        return switch (action) {
            case PROVISION -> this == PROVISION_ONLY || this == ALL;
            case DEPROVISION -> this == DEPROVISION_ONLY || this == ALL;
            case SUSPEND -> this == SUSPEND_ONLY || this == ALL;
            case RESUME -> this == RESUME_ONLY || this == ALL;
        };
    }

    public boolean any() {
        return this != NONE;
    }

    public HumanGating merge(HumanGating other) {
        if (this == ALL || other == ALL) {return ALL;}
        if (this == NONE) {return other;}
        if (other == NONE) {return this;}
        if (this == other) {return this;}
        return ALL;
    }
}
