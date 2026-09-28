package io.casehub.desiredstate.api;

public sealed interface ResumeResult {
    record Success() implements ResumeResult {}
    record Failed(String reason) implements ResumeResult {}
    record PendingApproval(NodeId nodeId, String planReference) implements ResumeResult {}
}
