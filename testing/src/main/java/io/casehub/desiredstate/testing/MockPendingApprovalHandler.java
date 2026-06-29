package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Programmable mock PendingApprovalHandler for testing.
 * Records all calls. Results configurable per node+action.
 */
public class MockPendingApprovalHandler implements PendingApprovalHandler {

    public final CopyOnWriteArrayList<RecordedPending> recorded = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<RecordedRejection> acknowledgedRejections = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, ApprovalCheckResult> checkResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StepOutcome> recordPendingResults = new ConcurrentHashMap<>();

    @Override
    public ApprovalCheckResult check(DesiredNode node, StepAction action, String tenancyId) {
        return checkResults.getOrDefault(key(node.id(), action), new ApprovalCheckResult.None());
    }

    @Override
    public StepOutcome recordPending(DesiredNode node, StepAction action,
                                      String tenancyId, String planReference) {
        recorded.add(new RecordedPending(node.id(), action, tenancyId, planReference));
        return recordPendingResults.getOrDefault(
            key(node.id(), action),
            new StepOutcome.Skipped("pending approval: " + planReference));
    }

    @Override
    public void acknowledgeRejection(DesiredNode node, StepAction action, String tenancyId) {
        acknowledgedRejections.add(new RecordedRejection(node.id(), action, tenancyId));
    }

    public void programCheck(NodeId nodeId, StepAction action, ApprovalCheckResult result) {
        checkResults.put(key(nodeId, action), result);
    }

    public void programRecordPending(NodeId nodeId, StepAction action, StepOutcome result) {
        recordPendingResults.put(key(nodeId, action), result);
    }

    public void clear() {
        recorded.clear();
        acknowledgedRejections.clear();
        checkResults.clear();
        recordPendingResults.clear();
    }

    private String key(NodeId nodeId, StepAction action) {
        return nodeId.value() + ":" + action.name();
    }

    public record RecordedPending(NodeId nodeId, StepAction action, String tenancyId, String planReference) {}
    public record RecordedRejection(NodeId nodeId, StepAction action, String tenancyId) {}
}
