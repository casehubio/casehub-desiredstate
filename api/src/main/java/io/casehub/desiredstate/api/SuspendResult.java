package io.casehub.desiredstate.api;

public sealed interface SuspendResult {
    record Success() implements SuspendResult {}
    record Failed(String reason) implements SuspendResult {}
    record PendingApproval(NodeId nodeId, String planReference) implements SuspendResult {}
}
