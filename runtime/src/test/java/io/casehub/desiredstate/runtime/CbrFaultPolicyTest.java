package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.AdaptedConfiguration;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;
import io.casehub.desiredstate.api.StepOutcome;
import io.casehub.desiredstate.api.TransitionResult;
import io.casehub.platform.api.preferences.MultiValuePreference;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SingleValuePreference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CbrFaultPolicyTest {

    private record TestSpec(String value) implements NodeSpec {}

    private StubRetriever retriever;
    private StubAdapter adapter;
    private PreferenceProvider prefProvider;
    private CbrProposalTracker tracker;
    private CbrFaultPolicy policy;

    @BeforeEach
    void setUp() {
        retriever = new StubRetriever();
        adapter = new StubAdapter();
        prefProvider = scope -> new MapPreferences(Map.of());
        tracker = new CbrProposalTracker();
        policy = new CbrFaultPolicy(retriever, adapter, prefProvider, tracker);
    }

    @Test
    void noCandidates_shouldReturnEmptyMutations() {
        retriever.setResults(List.of());

        List<GraphMutation> mutations = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void candidateBelowRetrievalThreshold_shouldBeFiltered() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("new"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.9, "case-1"));

        List<GraphMutation> mutations = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void candidateBelowAdaptationThreshold_shouldBeFiltered() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("new"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.8, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.4, "case-1"));

        List<GraphMutation> mutations = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void successfulAdaptation_shouldProduceMutations() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("fixed"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        List<GraphMutation> mutations = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).hasSize(1);
        assertThat(mutations.get(0)).isInstanceOf(GraphMutation.AddNode.class);
    }

    @Test
    void adapterReturnsEmpty_shouldReturnEmptyMutations() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty();
        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.9, "case-1", Map.of())));
        // adapter default is null → returns Optional.empty()

        List<GraphMutation> mutations = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).isEmpty();
    }

    @Test
    void shouldSelectHighestConfidenceAdaptation() {
        DesiredStateGraph low = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("low"), NodeType.of("t"), new TestSpec("low"), HumanGating.NONE));
        DesiredStateGraph high = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("high"), NodeType.of("t"), new TestSpec("high"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(low, 0.9, "case-low", Map.of()),
            new RetrievedConfiguration(high, 0.8, "case-high", Map.of())));
        adapter.setResultForSource("case-low", new AdaptedConfiguration(low, 0.65, "case-low"));
        adapter.setResultForSource("case-high", new AdaptedConfiguration(high, 0.95, "case-high"));

        List<GraphMutation> mutations = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "timeout"),
            ImmutableDesiredStateGraph.empty(),
            new ActualState(Map.of()));

        assertThat(mutations).hasSize(1);
        GraphMutation.AddNode add = (GraphMutation.AddNode) mutations.get(0);
        assertThat(add.node().id()).isEqualTo(NodeId.of("high"));
    }

    @Test
    void perCallPreferenceResolution_changedThreshold() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
            new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));

        retriever.setResults(List.of(
            new RetrievedConfiguration(adapted, 0.3, "case-1", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-1"));

        // Default threshold 0.5 → 0.3 below → filtered
        List<GraphMutation> mutations1 = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x"),
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));
        assertThat(mutations1).isEmpty();

        // Lower threshold to 0.2 via preferences
        prefProvider = scope -> new MapPreferences(Map.of(
            DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE.qualifiedName(), "0.2"));
        policy = new CbrFaultPolicy(retriever, adapter, prefProvider, tracker);

        List<GraphMutation> mutations2 = policy.onFault("tenant-1",
            new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "x"),
            ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));
        assertThat(mutations2).hasSize(1);
    }

    @Test
    void contextContainsFaultEvent() {
        List<RetrievalContext> captured = new ArrayList<>();
        ConfigurationRetriever capturingRetriever = (ctx, max) -> {
            captured.add(ctx);
            return List.of();
        };
        CbrFaultPolicy p = new CbrFaultPolicy(capturingRetriever, adapter, prefProvider, tracker);

        FaultEvent event = new FaultEvent(NodeId.of("n1"), FaultType.PROVISION_FAILED, "boom");
        p.onFault("tenant-1", event, ImmutableDesiredStateGraph.empty(), new ActualState(Map.of()));

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).faultEvent()).isEqualTo(event);
        assertThat(captured.get(0).situation()).isNull();
    }

    @Test
    void successfulAdaptation_recordsProposalInTracker() {
        DesiredStateGraph adapted = ImmutableDesiredStateGraph.empty().withNode(
                new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("fixed"), HumanGating.NONE));

        retriever.setResults(List.of(
                new RetrievedConfiguration(adapted, 0.9, "case-42", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(adapted, 0.8, "case-42"));

        policy.onFault("tenant-1",
                       new FaultEvent(NodeId.of("x1"), FaultType.PROVISION_FAILED, "timeout"),
                       ImmutableDesiredStateGraph.empty(),
                       new ActualState(Map.of()));

        var result   = new TransitionResult(Map.of(NodeId.of("n1"), new StepOutcome.Succeeded()));
        var outcomes = tracker.matchOutcomes("tenant-1", result, ImmutableDesiredStateGraph.empty());
        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).sourceId()).isEqualTo("case-42");
        assertThat(outcomes.get(0).path()).isEqualTo(CbrPath.FAULT);
    }

    @Test
    void noCandidates_doesNotRecordProposal() {
        retriever.setResults(List.of());

        policy.onFault("tenant-1",
                       new FaultEvent(NodeId.of("x1"), FaultType.PROVISION_FAILED, "timeout"),
                       ImmutableDesiredStateGraph.empty(),
                       new ActualState(Map.of()));

        var result = new TransitionResult(Map.of());
        assertThat(tracker.matchOutcomes("tenant-1", result, ImmutableDesiredStateGraph.empty())).isEmpty();
    }

    @Test
    void identicalGraphs_noMutations_doesNotRecordProposal() {
        DesiredStateGraph graph = ImmutableDesiredStateGraph.empty().withNode(
                new DesiredNode(NodeId.of("n1"), NodeType.of("t"), new TestSpec("v"), HumanGating.NONE));

        retriever.setResults(List.of(
                new RetrievedConfiguration(graph, 0.9, "case-99", Map.of())));
        adapter.setDefaultResult(new AdaptedConfiguration(graph, 0.8, "case-99"));

        policy.onFault("tenant-1",
                       new FaultEvent(NodeId.of("x1"), FaultType.PROVISION_FAILED, "timeout"),
                       graph, new ActualState(Map.of()));

        var result = new TransitionResult(Map.of());
        assertThat(tracker.matchOutcomes("tenant-1", result, ImmutableDesiredStateGraph.empty())).isEmpty();
    }


    static class StubRetriever implements ConfigurationRetriever {
        private List<RetrievedConfiguration> results = List.of();
        void setResults(List<RetrievedConfiguration> results) { this.results = results; }
        @Override
        public List<RetrievedConfiguration> retrieve(RetrievalContext context, int maxResults) {
            return results;
        }
    }

    static class StubAdapter implements ConfigurationAdapter {
        private AdaptedConfiguration defaultResult;
        private final Map<String, AdaptedConfiguration> bySourceId = new HashMap<>();

        void setDefaultResult(AdaptedConfiguration result) { this.defaultResult = result; }
        void setResultForSource(String sourceId, AdaptedConfiguration result) { bySourceId.put(sourceId, result); }

        @Override
        public Optional<AdaptedConfiguration> adapt(RetrievedConfiguration retrieved, RetrievalContext context) {
            AdaptedConfiguration specific = bySourceId.get(retrieved.sourceId());
            if (specific != null) return Optional.of(specific);
            return Optional.ofNullable(defaultResult);
        }
    }

    static class MapPreferences implements Preferences {
        private final Map<String, String> values;
        MapPreferences(Map<String, String> values) { this.values = values; }

        @Override
        public <T extends SingleValuePreference> T get(PreferenceKey<T> key) {
            String raw = values.get(key.qualifiedName());
            return raw != null ? key.parse(raw) : null;
        }
        @Override
        public <T extends MultiValuePreference> T get(PreferenceKey<T> key, String subKey) { return null; }
        @Override
        public Map<String, Object> asMap() { return Map.copyOf(values); }
    }
}
