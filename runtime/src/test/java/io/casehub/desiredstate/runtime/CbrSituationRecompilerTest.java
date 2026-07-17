package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.AdaptedConfiguration;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.ras.api.ActiveSituation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CbrSituationRecompilerTest {

    private record TestSpec(String value) implements NodeSpec {}

    private CbrFaultPolicyTest.StubRetriever retriever;
    private CbrFaultPolicyTest.StubAdapter adapter;
    private PreferenceProvider prefProvider;
    private CbrProposalTracker tracker;
    private CbrSituationRecompiler recompiler;

    private final ActiveSituation situation = new ActiveSituation(
        "sit-1", "zone-A", "tenant-1", 0.95,
        Map.of(), Instant.now().minusSeconds(60), Instant.now(), 3);

    @BeforeEach
    void setUp() {
        retriever = new CbrFaultPolicyTest.StubRetriever();
        adapter = new CbrFaultPolicyTest.StubAdapter();
        prefProvider = scope -> new CbrFaultPolicyTest.MapPreferences(Map.of());
        tracker = new CbrProposalTracker();
        recompiler = new CbrSituationRecompiler(retriever, adapter, prefProvider, tracker);
    }

    @Test
    void noCandidates_shouldReturnEmpty() {
        retriever.setResults(List.of());

        Optional<CompilationResult> result = recompiler.recompile("tenant-1",
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void successfulAdaptation_shouldReturnCompilationResult() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("fixed"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile("tenant-1",
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CompilationResult.SingleGraph.class);
        CompilationResult.SingleGraph sg = (CompilationResult.SingleGraph) result.get();
        assertThat(sg.graph().nodes()).containsKey(NodeId.of("n1"));
    }

    @Test
    void shouldHaveMaxIntPriority() {
        assertThat(recompiler.priority()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void actualStateIsPassedToRetrievalContext() {
        ActualState actual = new ActualState(Map.of(NodeId.of("x"), NodeStatus.PRESENT));

        List<RetrievalContext> captured = new ArrayList<>();
        ConfigurationRetriever capturingRetriever = (ctx, max) -> {
            captured.add(ctx);
            return List.of();
        };
        CbrSituationRecompiler r = new CbrSituationRecompiler(capturingRetriever, adapter, prefProvider, tracker);

        r.recompile("tenant-1", ImmutableDesiredStateGraph.empty(), actual, situation, null);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).actualState()).isSameAs(actual);
        assertThat(captured.get(0).situation()).isEqualTo(situation);
        assertThat(captured.get(0).faultEvent()).isNull();
    }

    @Test
    void belowRetrievalThreshold_shouldReturnEmpty() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.9, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile("tenant-1",
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void belowAdaptationThreshold_shouldReturnEmpty() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.8, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.4, "case-1"));

        Optional<CompilationResult> result = recompiler.recompile("tenant-1",
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        assertThat(result).isEmpty();
    }

    @Test
    void successfulAdaptation_recordsProposalInTracker() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
                new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("fixed"), HumanGating.NONE));

        retriever.setResults(List.of(
                new RetrievedConfiguration(adapted, 0.9, "case-42", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-42"));

        recompiler.recompile("tenant-1",
                             ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        var result   = new TransitionResult(Map.of(NodeId.of("n1"), new StepOutcome.Succeeded()));
        var outcomes = tracker.matchOutcomes("tenant-1", result, ImmutableDesiredStateGraph.empty());
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).sourceId()).isEqualTo("case-42");
        assertThat(outcomes.get(0).path()).isEqualTo(CbrPath.SITUATION);
    }

    @Test
    void noCandidates_doesNotRecordProposal() {
        retriever.setResults(List.of());

        recompiler.recompile("tenant-1",
                             ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()), situation, null);

        var result = new TransitionResult(Map.of());
        assertThat(tracker.matchOutcomes("tenant-1", result, ImmutableDesiredStateGraph.empty())).isEmpty();
    }
}
