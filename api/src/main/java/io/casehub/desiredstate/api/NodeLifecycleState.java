package io.casehub.desiredstate.api;

public enum NodeLifecycleState {
    ABSENT,
    PROVISIONING,
    PRESENT,
    DRIFTED,
    DEPROVISIONING,
    SUSPENDING,
    SUSPENDED,
    RESUMING;

    public boolean isTransient() {
        return this == PROVISIONING || this == DEPROVISIONING
               || this == SUSPENDING || this == RESUMING;
    }

    public static NodeLifecycleState fromNodeStatus(NodeStatus status) {
        return switch (status) {
            case PRESENT -> PRESENT;
            case ABSENT -> ABSENT;
            case DRIFTED -> DRIFTED;
            case SUSPENDED -> SUSPENDED;
            case UNKNOWN -> null;
        };
    }
}
