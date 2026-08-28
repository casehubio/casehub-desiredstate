package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphPatternMatcher;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;

import java.util.ArrayList;
import java.util.List;

public class CrossSurfaceRuleResolutionStep {

    @BuildStep
    void resolveRulesAcrossSurfaces(
            List<DesiredStateGraphBuildItem> graphs,
            List<StandaloneRuleBuildItem> standaloneRules,
            List<StandaloneInvariantBuildItem> standaloneInvariants,
            BuildProducer<AdditionalRulesBuildItem> additionalRules) {

        List<AdditionalRulesBuildItem> results = new ArrayList<>();
        resolve(graphs, standaloneRules, standaloneInvariants, results);
        results.forEach(additionalRules::produce);
    }

    static void resolve(List<DesiredStateGraphBuildItem> graphs,
                        List<StandaloneRuleBuildItem> standaloneRules,
                        List<StandaloneInvariantBuildItem> standaloneInvariants,
                        List<AdditionalRulesBuildItem> results) {
        for (DesiredStateGraphBuildItem graph : graphs) {
            if (!graph.source().startsWith("yaml:")) {continue;}

            String graphKey = graph.qualifiedName();
            List<GraphRuleDescriptor> matchedRules = new ArrayList<>();
            List<GraphInvariantDescriptor> matchedInvariants = new ArrayList<>();

            for (StandaloneRuleBuildItem sr : standaloneRules) {
                if (GraphPatternMatcher.matches(sr.graphPatterns(), graphKey)) {
                    matchedRules.addAll(sr.rules());
                }
            }
            for (StandaloneInvariantBuildItem si : standaloneInvariants) {
                if (GraphPatternMatcher.matches(si.graphPatterns(), graphKey)) {
                    matchedInvariants.addAll(si.invariants());
                }
            }

            if (!matchedRules.isEmpty() || !matchedInvariants.isEmpty()) {
                results.add(new AdditionalRulesBuildItem(
                        graph.namespace(), graph.name(),
                        matchedRules, matchedInvariants));
            }
        }
    }
}
