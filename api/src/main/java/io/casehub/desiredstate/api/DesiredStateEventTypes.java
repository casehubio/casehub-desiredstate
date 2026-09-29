package io.casehub.desiredstate.api;

public final class DesiredStateEventTypes {
    private DesiredStateEventTypes() {}

    public static final String RECONCILIATION_COMPLETED =
        "io.casehub.desiredstate.reconciliation.completed";
    public static final String NODE_FAULTED =
        "io.casehub.desiredstate.node.faulted";
    public static final String NODE_DRIFTED =
        "io.casehub.desiredstate.node.drifted";
    public static final String NODE_RECOVERED =
        "io.casehub.desiredstate.node.recovered";

    public static final String NODE_SUSPENDED =
            "io.casehub.desiredstate.node.suspended";
    public static final String NODE_RESUMED   =
            "io.casehub.desiredstate.node.resumed";
    public static final String LIFECYCLE_STATE_ENTERED =
            "io.casehub.desiredstate.lifecycle.state-entered";
    public static final String LIFECYCLE_STATE_EXITED =
            "io.casehub.desiredstate.lifecycle.state-exited";

}
