package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.yaml.model.YamlInvariant;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.model.YamlPattern;
import io.casehub.desiredstate.yaml.model.YamlRule;
import io.casehub.yaml.core.module.ModuleExpander;
import io.casehub.yaml.plugin.api.ParameterType;
import io.casehub.yaml.core.module.SectionContentRewriter;
import io.casehub.yaml.core.module.TypedExpandedModule;
import io.casehub.yaml.core.module.YamlImport;
import io.casehub.yaml.core.module.YamlModule;
import io.casehub.yaml.core.module.YamlModuleParameter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateModuleBridgeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DesiredStateModuleBridge bridge = new DesiredStateModuleBridge(mapper);

    // --- round-trip tests ---

    @Test
    void roundTrip_nodesPreserved() {
        var content = new DesiredStateModuleContent(
                Map.of("monitor", new YamlNode("monitor",
                        Map.of("target", "sink"), List.of("sink"),
                        null, null, null, null, null)),
                Map.of(), Map.of());

        var sections = bridge.toSections(content);
        var restored = bridge.fromSections(sections);

        assertThat(restored.nodes()).containsKey("monitor");
        assertThat(restored.nodes().get("monitor").type()).isEqualTo("monitor");
        assertThat(restored.nodes().get("monitor").dependsOn()).containsExactly("sink");
    }

    @Test
    void roundTrip_rulesAndInvariantsPreserved() {
        var rule = new YamlRule(null,
                Map.of("mon", new YamlPattern("monitor", null, null)),
                null, null, null,
                List.of(Map.of("addNode", Map.of("type", "alerter"))));

        var invariant = new YamlInvariant(null,
                Map.of("mon", new YamlPattern("monitor", null, null)),
                Map.of("target", new YamlPattern("*", "mon",
                        io.casehub.desiredstate.annotations.runtime.Direction.DEPENDENCIES)),
                null, null, null);

        var content = new DesiredStateModuleContent(
                Map.of(), Map.of("auto-alert", rule),
                Map.of("monitor-check", invariant));

        var sections = bridge.toSections(content);
        var restored = bridge.fromSections(sections);

        assertThat(restored.rules()).containsKey("auto-alert");
        assertThat(restored.invariants()).containsKey("monitor-check");
    }

    @Test
    void roundTrip_emptySections() {
        var content = DesiredStateModuleContent.empty();
        var sections = bridge.toSections(content);
        var restored = bridge.fromSections(sections);

        assertThat(restored.nodes()).isEmpty();
        assertThat(restored.rules()).isEmpty();
        assertThat(restored.invariants()).isEmpty();
    }

    // --- rewriter tests ---

    @Test
    void rewriter_aliasesDependencyInNodesSection() {
        SectionContentRewriter rewriter = bridge.rewriter();
        assertThat(rewriter).isNotNull();

        var nodeMap = new LinkedHashMap<String, Object>();
        nodeMap.put("type", "alerter");
        nodeMap.put("dependsOn", List.of("monitor"));

        Object rewritten = rewriter.rewrite("nodes", "alerter", nodeMap,
                "pipe-monitor", Set.of("monitor", "alerter"));

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) rewritten;
        @SuppressWarnings("unchecked")
        var deps = (List<Object>) result.get("dependsOn");
        assertThat(deps).containsExactly("pipe-monitor.monitor");
    }

    @Test
    void rewriter_preservesExternalDependency() {
        SectionContentRewriter rewriter = bridge.rewriter();

        var nodeMap = new LinkedHashMap<String, Object>();
        nodeMap.put("type", "monitor");
        nodeMap.put("dependsOn", List.of("${var.watched_node_id}"));

        Object rewritten = rewriter.rewrite("nodes", "monitor", nodeMap,
                "pipe-monitor", Set.of("monitor", "alerter"));

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) rewritten;
        @SuppressWarnings("unchecked")
        var deps = (List<Object>) result.get("dependsOn");
        assertThat(deps).containsExactly("${var.watched_node_id}");
    }

    @Test
    void rewriter_handlesOptionalDependency() {
        SectionContentRewriter rewriter = bridge.rewriter();

        var nodeMap = new LinkedHashMap<String, Object>();
        nodeMap.put("type", "alerter");
        nodeMap.put("dependsOn", List.of(Map.of("node", "monitor", "optional", true)));

        Object rewritten = rewriter.rewrite("nodes", "alerter", nodeMap,
                "pipe-monitor", Set.of("monitor", "alerter"));

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) rewritten;
        @SuppressWarnings("unchecked")
        var deps = (List<Object>) result.get("dependsOn");
        @SuppressWarnings("unchecked")
        var optDep = (Map<String, Object>) deps.get(0);
        assertThat(optDep.get("node")).isEqualTo("pipe-monitor.monitor");
        assertThat(optDep.get("optional")).isEqualTo(true);
    }

    @Test
    void rewriter_doesNotTouchNonNodesSections() {
        SectionContentRewriter rewriter = bridge.rewriter();

        var ruleMap = new LinkedHashMap<String, Object>();
        ruleMap.put("match", Map.of("mon", Map.of("type", "monitor")));

        Object rewritten = rewriter.rewrite("rules", "auto-alert", ruleMap,
                "pipe-monitor", Set.of("monitor", "alerter"));

        assertThat(rewritten).isSameAs(ruleMap);
    }

    // --- full expansion integration test ---

    @Test
    void fullExpand_aliasedNodesInTypedResult() {
        var monitoringModule = new YamlModule("monitoring",
                Map.of("watched_node_id",
                        YamlModuleParameter.builder()
                                .type(ParameterType.STRING)
                                .required().build()),
                Map.of(),
                Map.of("nodes", Map.<String, Object>of(
                        "monitor", Map.<String, Object>of(
                                "type", "monitor",
                                "dependsOn", List.of("${var.watched_node_id}"),
                                "spec", Map.of("target", "${var.watched_node_id}")),
                        "alerter", Map.<String, Object>of(
                                "type", "alerter",
                                "dependsOn", List.of("monitor"),
                                "spec", Map.of("email", "ops@example.com")))));

        var imports = List.of(new YamlImport("monitoring", "pipe-monitor", null,
                Map.of("watched_node_id", "warehouse-sink")));

        TypedExpandedModule<DesiredStateModuleContent> result =
                ModuleExpander.expand(imports,
                        Map.of("monitoring", monitoringModule),
                        DesiredStateModuleContent.empty(), bridge);

        assertThat(result.content().nodes()).containsKey("pipe-monitor.monitor");
        assertThat(result.content().nodes()).containsKey("pipe-monitor.alerter");
        assertThat(result.moduleScopes()).containsKey("pipe-monitor");
        assertThat(result.moduleScopes().get("pipe-monitor"))
                .containsEntry("watched_node_id", "warehouse-sink");

        YamlNode alerter = result.content().nodes().get("pipe-monitor.alerter");
        assertThat(alerter.dependsOn()).contains("pipe-monitor.monitor");
    }

    @Test
    void moduleOutputs_resolvedAndAccessibleViaOutputSource() {
        var monitoringModule = new YamlModule("monitoring",
                                              Map.of("watched_node_id",
                                                     YamlModuleParameter.builder()
                                                                        .type(ParameterType.STRING)
                                                                        .required().build()),
                                              Map.of("monitor_id", new io.casehub.yaml.core.module.YamlModuleOutput(
                                                      ParameterType.STRING, "${var.watched_node_id}-monitor")),
                                              Map.of("nodes", Map.<String, Object>of(
                                                      "monitor", Map.<String, Object>of(
                                                              "type", "monitor",
                                                              "spec", Map.of("target", "${var.watched_node_id}")))));

        var imports = List.of(new YamlImport("monitoring", "pipe-mon", null,
                                             Map.of("watched_node_id", "warehouse-sink")));

        TypedExpandedModule<DesiredStateModuleContent> result =
                ModuleExpander.expand(imports,
                                      Map.of("monitoring", monitoringModule),
                                      DesiredStateModuleContent.empty(), bridge);

        assertThat(result.moduleOutputs()).containsKey("pipe-mon");
        assertThat(result.moduleOutputs().get("pipe-mon"))
                .containsEntry("monitor_id", "warehouse-sink-monitor");

        io.casehub.yaml.core.resolver.VariableSource outputSource = result.outputSource();
        assertThat(outputSource.resolve("pipe-mon.monitor_id"))
                .isEqualTo("warehouse-sink-monitor");
    }

}
