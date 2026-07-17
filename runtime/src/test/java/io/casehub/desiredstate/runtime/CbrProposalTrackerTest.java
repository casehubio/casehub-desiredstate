package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.CbrPath;
import io.casehub.desiredstate.api.CbrProposal;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CbrProposalTrackerTest {

    private record TestSpec(String value) implements NodeSpec {}

    private CbrProposalTracker tracker;
    private final DefaultDesiredStateGraphFactory factory = new DefaultDesiredStateGraphFactory();

    @BeforeEach
    void setUp() {
        tracker = new CbrProposalTracker();
    }

    @Test
    void noPendingProposals_returnsEmpty() {
        var result = new TransitionResult(Map.of());
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).isEmpty();
    }

    @Test
    void proposalMatchedWithSucceeded() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).successCount()).isEqualTo(1);
        assertThat(outcomes.get(0).failureCount()).isEqualTo(0);
        assertThat(outcomes.get(0).resolvedCount()).isEqualTo(1);
        assertThat(outcomes.get(0).successRate()).isEqualTo(1.0);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("SUCCEEDED");
        assertThat(outcomes.get(0).sourceId()).isEqualTo("src-1");
        assertThat(outcomes.get(0).path()).isEqualTo(CbrPath.FAULT);
    }

    @Test
    void proposalMatchedWithFailed() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Failed("err")));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).failureCount()).isEqualTo(1);
        assertThat(outcomes.get(0).successRate()).isEqualTo(0.0);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("FAILED");
    }

    @Test
    void proposalMatchedWithRejected_countsAsFailure() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Rejected("denied")));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes.get(0).failureCount()).isEqualTo(1);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("REJECTED");
    }

    @Test
    void nodeNotInResultButInGraph_alreadyPresent() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of());
        var node = new DesiredNode(nodeId, new NodeType("t"), new TestSpec("v"), HumanGating.NONE);
        var graph = factory.of(java.util.List.of(node), Set.of());
        var outcomes = tracker.matchOutcomes("t1", result, graph);

        assertThat(outcomes.get(0).successCount()).isEqualTo(1);
        assertThat(outcomes.get(0).nodeOutcomes().get("n1")).isEqualTo("ALREADY_PRESENT");
    }

    @Test
    void nodeNotInResultNotInGraph_superseded_noOutcome() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of());
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes).isEmpty();
    }

    @Test
    void allNodesSkipped_noOutcomeEmitted() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Skipped("skip")));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes).isEmpty();
    }

    @Test
    void mixedOutcomes_partialSuccess() {
        var n1 = new NodeId("n1");
        var n2 = new NodeId("n2");
        var n3 = new NodeId("n3");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(n1, n2, n3), Instant.now()));

        var result = new TransitionResult(Map.of(
            n1, new StepOutcome.Succeeded(),
            n2, new StepOutcome.Failed("err"),
            n3, new StepOutcome.Succeeded()));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes.get(0).successCount()).isEqualTo(2);
        assertThat(outcomes.get(0).failureCount()).isEqualTo(1);
        assertThat(outcomes.get(0).resolvedCount()).isEqualTo(3);
        assertThat(outcomes.get(0).successRate()).isCloseTo(0.6667, within(0.001));
    }

    @Test
    void multipleProposals_matchedIndependently() {
        var n1 = new NodeId("n1");
        var n2 = new NodeId("n2");
        tracker.recordProposal("t1", new CbrProposal(
            "src-A", CbrPath.FAULT, Set.of(n1), Instant.now()));
        tracker.recordProposal("t1", new CbrProposal(
            "src-B", CbrPath.SITUATION, Set.of(n2), Instant.now()));

        var result = new TransitionResult(Map.of(
            n1, new StepOutcome.Succeeded(),
            n2, new StepOutcome.Failed("err")));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).sourceId()).isEqualTo("src-A");
        assertThat(outcomes.get(0).successRate()).isEqualTo(1.0);
        assertThat(outcomes.get(1).sourceId()).isEqualTo("src-B");
        assertThat(outcomes.get(1).successRate()).isEqualTo(0.0);
    }

    @Test
    void clearTenant_removesPendingProposals() {
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(new NodeId("n1")), Instant.now()));
        tracker.clearTenant("t1");

        var result = new TransitionResult(Map.of(new NodeId("n1"), new StepOutcome.Succeeded()));
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).isEmpty();
    }

    @Test
    void differentTenants_noInterference() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        assertThat(tracker.matchOutcomes("t2", result, factory.empty())).isEmpty();
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).hasSize(1);
    }

    @Test
    void matchOutcomes_consumesProposals() {
        var nodeId = new NodeId("n1");
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), Instant.now()));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        tracker.matchOutcomes("t1", result, factory.empty());
        assertThat(tracker.matchOutcomes("t1", result, factory.empty())).isEmpty();
    }

    @Test
    void outcomeData_hasCorrectTimestamps() {
        var nodeId = new NodeId("n1");
        var proposedAt = Instant.now().minusSeconds(10);
        tracker.recordProposal("t1", new CbrProposal(
            "src-1", CbrPath.FAULT, Set.of(nodeId), proposedAt));

        var result = new TransitionResult(Map.of(nodeId, new StepOutcome.Succeeded()));
        var outcomes = tracker.matchOutcomes("t1", result, factory.empty());

        assertThat(outcomes.get(0).proposedAt()).isEqualTo(proposedAt);
        assertThat(outcomes.get(0).observedAt()).isAfter(proposedAt);
    }
}
