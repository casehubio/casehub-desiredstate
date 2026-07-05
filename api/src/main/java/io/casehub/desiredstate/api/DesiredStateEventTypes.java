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
}
