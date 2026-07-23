package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.AdaptedConfiguration;
import io.casehub.desiredstate.api.CbrConfiguration;
import io.casehub.desiredstate.api.CbrPath;
import io.casehub.desiredstate.api.CbrProposal;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.ConfigurationAdapter;
import io.casehub.desiredstate.api.ConfigurationRetriever;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.RetrievalContext;
import io.casehub.desiredstate.api.RetrievedConfiguration;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.ras.api.ActiveSituation;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class CbrSituationRecompiler implements SituationRecompiler {

    private static final Logger LOG = Logger.getLogger(CbrSituationRecompiler.class.getName());

    private final ConfigurationRetriever retriever;
    private final ConfigurationAdapter adapter;
    private final PreferenceProvider preferenceProvider;
    private final CbrProposalTracker tracker;

    public CbrSituationRecompiler(ConfigurationRetriever retriever,
                                   ConfigurationAdapter adapter,
                                   PreferenceProvider preferenceProvider,
                                   CbrProposalTracker tracker) {
        this.retriever = retriever;
        this.adapter = adapter;
        this.preferenceProvider = preferenceProvider;
        this.tracker = tracker;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Optional<CompilationResult> recompile(
            String tenancyId,
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        CbrConfiguration config = resolveConfiguration(tenancyId);

        RetrievalContext context = RetrievalContext.forSituation(current, actual, situation);

        List<RetrievedConfiguration> candidates = retriever.retrieve(context, config.maxCandidates());
        if (candidates.isEmpty()) {
            LOG.log(Level.INFO, "cbr.no-candidate: no similar configurations found for situation {0}",
                    situation.situationId());
            return Optional.empty();
        }

        LOG.log(Level.INFO, "cbr.retrieve: {0} candidate(s), top confidence={1}",
                new Object[]{candidates.size(), candidates.stream()
                                                          .mapToDouble(RetrievedConfiguration::confidence).max().orElse(0.0)});

        Optional<AdaptedConfiguration> best = candidates.stream()
                                                        .filter(c -> c.confidence() >= config.minimumRetrievalConfidence())
                                                        .map(c -> adapter.adapt(c, context))
                                                        .flatMap(Optional::stream)
                                                        .filter(a -> a.confidence() >= config.minimumAdaptationConfidence())
                                                        .max(Comparator.comparingDouble(AdaptedConfiguration::confidence));

        if (best.isEmpty()) {
            LOG.log(Level.INFO, "cbr.no-candidate: no candidate survived confidence gates for situation {0}",
                    situation.situationId());
            return Optional.empty();
        }

        AdaptedConfiguration selected = best.get();
        LOG.log(Level.INFO, "cbr.selected: sourceId={0}, confidence={1}, path=situation",
                new Object[]{selected.sourceId(), selected.confidence()});

        Set<NodeId> affectedNodeIds = GraphDiff.computeMutations(current, selected.graph())
            .stream()
            .map(GraphDiff::targetNodeId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        if (!affectedNodeIds.isEmpty()) {
            tracker.recordProposal(tenancyId, new CbrProposal(
                selected.sourceId(), CbrPath.SITUATION, affectedNodeIds, Instant.now()));
        }

        return Optional.of(CompilationResult.single(selected.graph()));
    }

    private CbrConfiguration resolveConfiguration(String tenancyId) {
        Preferences prefs         = preferenceProvider.resolve(SettingsScope.root(tenancyId));
        double      minRetrieval  = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_RETRIEVAL_CONFIDENCE).value();
        double      minAdaptation = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MIN_ADAPTATION_CONFIDENCE).value();
        int         maxCandidates = prefs.getOrDefault(DesiredStatePreferenceKeys.CBR_MAX_CANDIDATES).value();
        return new CbrConfiguration(minRetrieval, minAdaptation, maxCandidates);
    }
}
