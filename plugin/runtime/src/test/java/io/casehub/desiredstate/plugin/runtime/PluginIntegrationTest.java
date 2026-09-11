package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.plugin.runtime.primitives.CompareStatePrimitive;
import io.casehub.yaml.step.PrimitiveRegistry;
import io.casehub.yaml.step.StepDef;
import io.casehub.yaml.step.StepPipelineExecutor;
import io.casehub.yaml.step.primitives.AssertPrimitive;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PluginIntegrationTest {

    private static final NodeType MOCK_TYPE = NodeType.of("mock-resource");
    private static PluginModel pluginModel;

    @BeforeAll
    static void parsePlugin() throws IOException {
        try (var is = PluginIntegrationTest.class.getResourceAsStream(
                "/META-INF/desiredstate/plugins/mock-resource.yaml")) {
            pluginModel = PluginParser.parse(is);
        }
    }

    @Test
    void pluginYamlParsesCorrectly() {
        assertThat(pluginModel.header().type()).isEqualTo("mock-resource");
        assertThat(pluginModel.header().version()).isEqualTo(1);
        assertThat(pluginModel.spec().fields()).containsKeys("name", "status-code");
        assertThat(pluginModel.actualStateSteps()).hasSize(1);
        assertThat(pluginModel.provisioner().provisionSteps()).hasSize(1);
        assertThat(pluginModel.provisioner().deprovisionSteps()).hasSize(1);
    }

    @Test
    void provisionerEndToEnd() {
        var provisioner = createProvisioner();

        assertThat(provisioner.handledTypes()).containsExactly(MOCK_TYPE);

        var node = createNode("n1", Map.of("name", "myapp", "status-code", 200));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node, new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void deprovisionerEndToEnd() {
        var provisioner = createProvisioner();

        var node = createNode("n1", Map.of("name", "myapp", "status-code", 200));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.deprovision(node, new DeprovisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void actualStatePresent() {
        var adapter = createAdapter();
        var node = createNode("n1", Map.of("name", "myapp", "status-code", 200));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1"))).hasValue(NodeStatus.PRESENT);
    }

    @Test
    void actualStateAbsent() {
        var adapter = createAdapter();
        var node = createNode("n1", Map.of("name", "gone", "status-code", 404));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1"))).hasValue(NodeStatus.ABSENT);
    }

    @Test
    void actualStateDrifted() {
        var adapter = createAdapter();
        var node = createNode("n1", Map.of("name", "partial", "status-code", 206));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1"))).hasValue(NodeStatus.DRIFTED);
    }

    @Test
    void rasRegistrationEndToEnd() {
        var registrar = new YamlPluginRasRegistrar("mock-resource", pluginModel.ras());
        var registrations = registrar.registrations();

        assertThat(registrations).hasSize(1);
        var def = registrations.get(0).definition();
        assertThat(def.situationId()).isEqualTo("plugin.mock-resource.repeated-provision-failure");
        assertThat(def.correlationWindow()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void cbrMetadataEndToEnd() {
        var metadata = CbrPluginMetadata.from("mock-resource", pluginModel.cbr());

        assertThat(metadata.pluginType()).isEqualTo("mock-resource");
        assertThat(metadata.features()).hasSize(1);
        assertThat(metadata.features().get(0).name()).isEqualTo("status");
        assertThat(metadata.outcomeSignals()).containsKey("success");
    }

    @Test
    void multipleNodesInGraph() {
        var adapter = createAdapter();
        var n1 = createNode("n1", Map.of("name", "app1", "status-code", 200));
        var n2 = createNode("n2", Map.of("name", "app2", "status-code", 404));
        var n3 = createNode("n3", Map.of("name", "app3", "status-code", 206));
        var graph = new DefaultDesiredStateGraphFactory().empty()
            .withNode(n1).withNode(n2).withNode(n3);

        var actual = adapter.readActual(graph, "tenant1");
        assertThat(actual.statusOf(NodeId.of("n1"))).hasValue(NodeStatus.PRESENT);
        assertThat(actual.statusOf(NodeId.of("n2"))).hasValue(NodeStatus.ABSENT);
        assertThat(actual.statusOf(NodeId.of("n3"))).hasValue(NodeStatus.DRIFTED);
    }

    private YamlPluginProvisioner createProvisioner() {
        var descriptor = toDescriptor(pluginModel);
        return new YamlPluginProvisioner(
            Map.of(MOCK_TYPE, descriptor), createExecutor(),
            ref -> Map.of("token", "mock-token", "endpoint", "mock.api.local"));
    }

    private YamlPluginActualStateAdapter createAdapter() {
        var descriptor = toDescriptor(pluginModel);
        return new YamlPluginActualStateAdapter(
            Map.of(MOCK_TYPE, descriptor), createExecutor(),
            ref -> Map.of("token", "mock-token", "endpoint", "mock.api.local"));
    }

    private StepPipelineExecutor createExecutor() {
        var registry = PrimitiveRegistry.of(Map.of(
            "assert", new AssertPrimitive(),
            "compare-state", new CompareStatePrimitive()));
        return new StepPipelineExecutor(registry);
    }

    private static PluginDescriptor toDescriptor(PluginModel model) {
        return new PluginDescriptor(
            model.header().type(),
            model.header().version(),
            Duration.ofSeconds(30),
            model.header().auth() != null
                ? model.header().auth().entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> e.getValue().credentialRef()))
                : Map.of(),
            model.spec(),
            model.actualStateSteps(),
            model.provisioner().provisionSteps(),
            model.provisioner().deprovisionSteps(),
            model.faultPolicies(),
            model.cbr(),
            model.ras());
    }

    private static DesiredNode createNode(String id, Map<String, Object> specFields) {
        return new DesiredNode(
            NodeId.of(id),
            new YamlNodeSpec(MOCK_TYPE, HumanGating.NONE, specFields),
            HumanGating.NONE);
    }
}
