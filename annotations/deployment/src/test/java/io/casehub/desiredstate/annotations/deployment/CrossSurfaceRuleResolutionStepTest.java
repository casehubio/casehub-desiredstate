package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.PatternKind;
import io.casehub.desiredstate.annotations.runtime.PatternParameterDescriptor;
import io.casehub.desiredstate.annotations.runtime.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSurfaceRuleResolutionStepTest {

    @Test
    void matchesStandaloneRuleToYamlGraph() {
        var graphs = List.of(
                new DesiredStateGraphBuildItem("pipeline", "medallion", "yaml:medallion.yaml"),
                new DesiredStateGraphBuildItem("tutorial", "store", "annotation:Store"));

        var ruleDesc = new GraphRuleDescriptor(
                "ensureMonitoring", false,
                List.of(new PatternParameterDescriptor(
                        PatternKind.MATCH, "sink", "", Direction.DEPENDENCIES)),
                "com.example.MonitorRule");

        var standaloneRules = List.of(
                new StandaloneRuleBuildItem(new String[]{"*:*"}, List.of(ruleDesc)));

        List<AdditionalRulesBuildItem> results = new ArrayList<>();
        CrossSurfaceRuleResolutionStep.resolve(graphs, standaloneRules, List.of(), results);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).qualifiedName()).isEqualTo("pipeline:medallion");
        assertThat(results.get(0).rules()).hasSize(1);
    }

    @Test
    void namespacePatternFiltersCorrectly() {
        var graphs = List.of(
                new DesiredStateGraphBuildItem("pipeline", "medallion", "yaml:medallion.yaml"),
                new DesiredStateGraphBuildItem("tutorial", "store", "yaml:store.yaml"));

        var ruleDesc = new GraphRuleDescriptor(
                "pipelineOnly", false, List.of(), "com.example.PipelineRule");

        var standaloneRules = List.of(
                new StandaloneRuleBuildItem(new String[]{"pipeline:*"}, List.of(ruleDesc)));

        List<AdditionalRulesBuildItem> results = new ArrayList<>();
        CrossSurfaceRuleResolutionStep.resolve(graphs, standaloneRules, List.of(), results);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).qualifiedName()).isEqualTo("pipeline:medallion");
    }

    @Test
    void noMatchProducesNoItems() {
        var graphs = List.of(
                new DesiredStateGraphBuildItem("tutorial", "store", "yaml:store.yaml"));

        var ruleDesc = new GraphRuleDescriptor(
                "pipelineOnly", false, List.of(), "com.example.PipelineRule");

        var standaloneRules = List.of(
                new StandaloneRuleBuildItem(new String[]{"pipeline:*"}, List.of(ruleDesc)));

        List<AdditionalRulesBuildItem> results = new ArrayList<>();
        CrossSurfaceRuleResolutionStep.resolve(graphs, standaloneRules, List.of(), results);

        assertThat(results).isEmpty();
    }

    @Test
    void invariantsMatchedAlongside() {
        var graphs = List.of(
                new DesiredStateGraphBuildItem("pipeline", "medallion", "yaml:medallion.yaml"));

        var invDesc = new GraphInvariantDescriptor(
                "sinkHasUpstream", false, List.of(), "com.example.SinkInvariant");

        var standaloneInvariants = List.of(
                new StandaloneInvariantBuildItem(new String[]{"*:*"}, List.of(invDesc)));

        List<AdditionalRulesBuildItem> results = new ArrayList<>();
        CrossSurfaceRuleResolutionStep.resolve(graphs, List.of(), standaloneInvariants, results);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).invariants()).hasSize(1);
        assertThat(results.get(0).rules()).isEmpty();
    }

    @Test
    void annotationGraphsSkipped() {
        var graphs = List.of(
                new DesiredStateGraphBuildItem("pipeline", "annotated", "annotation:Pipeline"));

        var ruleDesc = new GraphRuleDescriptor(
                "globalRule", false, List.of(), "com.example.Rule");

        var standaloneRules = List.of(
                new StandaloneRuleBuildItem(new String[]{"*:*"}, List.of(ruleDesc)));

        List<AdditionalRulesBuildItem> results = new ArrayList<>();
        CrossSurfaceRuleResolutionStep.resolve(graphs, standaloneRules, List.of(), results);

        assertThat(results).isEmpty();
    }
}
