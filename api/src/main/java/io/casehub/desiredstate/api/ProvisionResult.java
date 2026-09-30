package io.casehub.desiredstate.api;

public sealed interface ProvisionResult {
    record Success() implements ProvisionResult {}

    record AlreadyConverged() implements ProvisionResult {}

    record Failed(String reason) implements ProvisionResult {}
    record PendingApproval(NodeId nodeId, String planReference) implements ProvisionResult {}
}
