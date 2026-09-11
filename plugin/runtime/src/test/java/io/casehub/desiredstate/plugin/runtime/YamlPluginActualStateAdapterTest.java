package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.plugin.runtime.primitives.CompareStatePrimitive;
import io.casehub.yaml.step.PrimitiveRegistry;
import io.casehub.yaml.step.StepDef;
import io.casehub.yaml.step.StepPipelineExecutor;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlPluginActualStateAdapterTest {

    private static final NodeType TEST_TYPE = NodeType.of("test-resource");

    @Test
    void readsActualStateForPluginTypes() {
        var compareStep = new StepDef("compare-state", Map.of(
            "present-when", "${spec.status} == 1",
            "absent-when", "${spec.status} == 0"),
            null, null, null, 3, null);

        var descriptor = new PluginDescriptor(
            "test-resource", 1, Duration.ofMinutes(5), Map.of(),
            new PluginSpecSchema(Map.of()), List.of(compareStep),
            List.of(), List.of(), List.of(), null, null);

        var registry = PrimitiveRegistry.of(Map.of(
            "compare-state", new CompareStatePrimitive()));
        var executor = new StepPipelineExecutor(registry);
        var adapter = new YamlPluginActualStateAdapter(
            Map.of(TEST_TYPE, descriptor), executor, ref -> Map.of());

        var node = createNode("n1", Map.of("status", 1));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1")))
            .hasValue(NodeStatus.PRESENT);
    }

    @Test
    void returnsAbsentForAbsentNode() {
        var compareStep = new StepDef("compare-state", Map.of(
            "present-when", "${spec.status} == 1",
            "absent-when", "${spec.status} == 0"),
            null, null, null, 3, null);

        var descriptor = new PluginDescriptor(
            "test-resource", 1, Duration.ofMinutes(5), Map.of(),
            new PluginSpecSchema(Map.of()), List.of(compareStep),
            List.of(), List.of(), List.of(), null, null);

        var registry = PrimitiveRegistry.of(Map.of(
            "compare-state", new CompareStatePrimitive()));
        var executor = new StepPipelineExecutor(registry);
        var adapter = new YamlPluginActualStateAdapter(
            Map.of(TEST_TYPE, descriptor), executor, ref -> Map.of());

        var node = createNode("n1", Map.of("status", 0));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1")))
            .hasValue(NodeStatus.ABSENT);
    }

    @Test
    void skipsNodesNotHandledByPlugin() {
        var compareStep = new StepDef("compare-state", Map.of(
            "present-when", "${spec.status} == 1"),
            null, null, null, 3, null);

        var descriptor = new PluginDescriptor(
            "test-resource", 1, Duration.ofMinutes(5), Map.of(),
            new PluginSpecSchema(Map.of()), List.of(compareStep),
            List.of(), List.of(), List.of(), null, null);

        var registry = PrimitiveRegistry.of(Map.of(
            "compare-state", new CompareStatePrimitive()));
        var executor = new StepPipelineExecutor(registry);
        var adapter = new YamlPluginActualStateAdapter(
            Map.of(TEST_TYPE, descriptor), executor, ref -> Map.of());

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

        var registry = PrimitiveRegistry.of(Map.of());
        var executor = new StepPipelineExecutor(registry);
        var adapter = new YamlPluginActualStateAdapter(
            Map.of(TEST_TYPE, descriptor), executor, ref -> Map.of());

        assertThat(adapter.handledTypes()).containsExactly(TEST_TYPE);
    }

    private static DesiredNode createNode(String id, Map<String, Object> specFields) {
        return new DesiredNode(
            NodeId.of(id),
            new YamlNodeSpec(TEST_TYPE, HumanGating.NONE, specFields),
            HumanGating.NONE);
    }
}
