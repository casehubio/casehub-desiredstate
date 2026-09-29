package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.plugin.runtime.primitives.CompareStatePrimitive;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.Definition;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlPluginActualStateAdapterTest {

    private static final NodeType TEST_TYPE = NodeType.of("test-resource");


    private static boolean evaluateExpression(String expr) {
        String trimmed = expr.trim();
        if (trimmed.contains("==")) {
            String[] parts = trimmed.split("==", 2);
            return parts[0].trim().equals(parts[1].trim());
        }
        if (trimmed.contains("<")) {
            String[] parts = trimmed.split("<", 2);
            try {
                return Integer.parseInt(parts[0].trim()) < Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return Boolean.parseBoolean(trimmed);
    }

    private YamlPluginActualStateAdapter createAdapter(PluginDescriptor descriptor) {
        var condEval         = new ConditionEvaluator(YamlPluginActualStateAdapterTest::evaluateExpression);
        var comparePrimitive = new CompareStatePrimitive(condEval);
        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps && "compare-state".equals(ps.name())) {
                Map<String, Object> resolved = resolver.resolveMap(ps.params(), "step");
                return comparePrimitive.execute(resolved, new MapServiceRegistry());
            }
            return Result.failed("unknown step: " + step);
        };
        var evaluator = new StructuralStepEvaluator(condEval);
        return new YamlPluginActualStateAdapter(
                Map.of(TEST_TYPE, descriptor), evaluator, runner, ref -> Map.of());
    }

    @Test
    void readsActualStateForPluginTypes() {
        var compareStep = pluginStep("compare-state", Map.of(
                "present-when", "${spec.status} == 1",
                "absent-when", "${spec.status} == 0"));

        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(compareStep),
                List.of(), List.of(), List.of(), null, null);

        var adapter = createAdapter(descriptor);
        var node    = createNode("n1", Map.of("status", 1));
        var graph   = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1")))
                .hasValue(NodeStatus.PRESENT);
    }

    @Test
    void returnsAbsentForAbsentNode() {
        var compareStep = pluginStep("compare-state", Map.of(
                "present-when", "${spec.status} == 1",
                "absent-when", "${spec.status} == 0"));

        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(compareStep),
                List.of(), List.of(), List.of(), null, null);

        var adapter = createAdapter(descriptor);
        var node    = createNode("n1", Map.of("status", 0));
        var graph   = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1")))
                .hasValue(NodeStatus.ABSENT);
    }

    @Test
    void skipsNodesNotHandledByPlugin() {
        var compareStep = pluginStep("compare-state", Map.of(
                "present-when", "${spec.status} == 1"));

        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(compareStep),
                List.of(), List.of(), List.of(), null, null);

        var adapter = createAdapter(descriptor);

        var pluginNode = createNode("n1", Map.of("status", 1));
        var otherNode = new DesiredNode(
                NodeId.of("n2"),
                new YamlNodeSpec(NodeType.of("other-type"), HumanGating.NONE,
                                 Map.of("status", 1)),
                HumanGating.NONE);
        var graph = new DefaultDesiredStateGraphFactory().empty()
                                                         .withNode(pluginNode)
                                                         .withNode(otherNode);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1")))
                .hasValue(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("n2"))).isEmpty();
    }

    @Test
    void handledTypesReturnsPluginTypes() {
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(), List.of(), List.of(), null, null);

        var adapter = createAdapter(descriptor);
        assertThat(adapter.handledTypes()).containsExactly(TEST_TYPE);
    }

    private static ResolvedStep pluginStep(String name, Map<String, Object> params) {
        return new ResolvedStep.PluginStep(name,
                                           Definition.of(name).execute((p, s) -> Result.of(Map.of())).build(), params, Map.of());
    }

    private static DesiredNode createNode(String id, Map<String, Object> specFields) {
        return new DesiredNode(
                NodeId.of(id),
                new YamlNodeSpec(TEST_TYPE, HumanGating.NONE, specFields),
                HumanGating.NONE);
    }
}
