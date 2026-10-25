package io.casehub.desiredstate.example.plugin.yaml;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.ResumeContext;
import io.casehub.desiredstate.api.ResumeResult;
import io.casehub.desiredstate.api.SuspendContext;
import io.casehub.desiredstate.api.SuspendResult;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
import io.casehub.desiredstate.plugin.runtime.PluginValidator;
import io.casehub.desiredstate.plugin.runtime.YamlPluginProvisioner;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MonitoringEndpointIntegrationTest {

    private static final NodeType MONITORING_TYPE = NodeType.of("monitoring-endpoint");
    private static PluginModel pluginModel;

    @BeforeAll
    static void parsePlugin() throws IOException {
        try (var is = MonitoringEndpointIntegrationTest.class.getResourceAsStream(
                "/META-INF/desiredstate/plugins/monitoring-endpoint.yaml")) {
            pluginModel = PluginParser.parse(is);
        }
    }

    @Test
    void pluginYamlParsesCorrectly() {
        assertThat(pluginModel.header().type()).isEqualTo("monitoring-endpoint");
        assertThat(pluginModel.header().version()).isEqualTo(1);
        assertThat(pluginModel.spec().fields()).containsKeys("url", "name", "interval", "severity", "tags");
        assertThat(pluginModel.actualStateSteps()).hasSize(2);
        assertThat(pluginModel.provisioner().provisionSteps()).hasSize(2);
        assertThat(pluginModel.provisioner().deprovisionSteps()).hasSize(2);
        assertThat(pluginModel.provisioner().suspendSteps()).hasSize(2);
        assertThat(pluginModel.provisioner().resumeSteps()).hasSize(2);
    }

    @Test
    void pluginPassesValidation() {
        assertThatCode(() ->
            PluginValidator.validatePlugin(pluginModel, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES))
            .doesNotThrowAnyException();
    }

    @Test
    void provisionerHandlesMonitoringType() {
        var provisioner = createProvisioner();
        assertThat(provisioner.handledTypes()).containsExactly(MONITORING_TYPE);
    }

    @Test
    void provisionSucceeds() {
        var provisioner = createProvisioner();
        var node  = createNode("api-health", Map.of(
                "url", "https://api.example.com/health",
                "name", "api-health",
                "interval", 15,
                "severity", "critical"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node, new ProvisionContext("tenant1", graph));
        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void deprovisionSucceeds() {
        var provisioner = createProvisioner();
        var node  = createNode("api-health", Map.of(
                "url", "https://api.example.com/health",
                "name", "api-health"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.deprovision(node, new DeprovisionContext("tenant1", graph));
        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void suspendSucceeds() {
        var provisioner = createProvisioner();
        var node  = createNode("api-health", Map.of(
                "url", "https://api.example.com/health",
                "name", "api-health"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.suspend(node, new SuspendContext("tenant1", graph));
        assertThat(result).isInstanceOf(SuspendResult.Success.class);
    }

    @Test
    void resumeSucceeds() {
        var provisioner = createProvisioner();
        var node  = createNode("api-health", Map.of(
                "url", "https://api.example.com/health",
                "name", "api-health"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.resume(node, new ResumeContext("tenant1", graph));
        assertThat(result).isInstanceOf(ResumeResult.Success.class);
    }

    @Test
    void supportsStatefulLifecycle() {
        var provisioner = createProvisioner();
        assertThat(provisioner.supportsStatefulLifecycle()).isTrue();
    }

    @Test
    void resyncIntervalFromPlugin() {
        var provisioner = createProvisioner();
        assertThat(provisioner.resyncInterval()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void faultPolicyDefined() {
        assertThat(pluginModel.faultPolicies()).hasSize(1);
        assertThat(pluginModel.faultPolicies().get(0).namespace())
                .isEqualTo("monitoring-escalation");
    }

    @Test
    void rasSituationDefined() {
        assertThat(pluginModel.ras().situations()).hasSize(1);
        assertThat(pluginModel.ras().situations().get(0).name())
                .isEqualTo("endpoint-flapping");
    }

    private YamlPluginProvisioner createProvisioner() {
        var descriptor = toDescriptor(pluginModel);
        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps && "assert".equals(ps.name())) {
                return Result.of(Map.of());
            }
            return Result.of(Map.of("status", 200));
        };
        var evaluator = new StructuralStepEvaluator(new ConditionEvaluator(null));
        return new YamlPluginProvisioner(
                Map.of(MONITORING_TYPE, descriptor), evaluator, runner,
                ref -> Map.of("endpoint", "mock.monitoring.local"));
    }

    private static PluginDescriptor toDescriptor(PluginModel model) {
        Map<String, String> authRefs = model.header().auth() != null
                ? model.header().auth().entrySet().stream()
                       .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().credentialRef()))
                : Map.of();
        return new PluginDescriptor(
                model.header().type(),
                model.header().version(),
                Duration.ofSeconds(60),
                authRefs,
                model.spec(),
                model.actualStateSteps(),
                model.provisioner().provisionSteps(),
                model.provisioner().deprovisionSteps(),
                model.provisioner().suspendSteps(),
                model.provisioner().resumeSteps(),
                model.faultPolicies(),
                model.cbr(),
                model.ras());
    }

    private static DesiredNode createNode(String id, Map<String, Object> specFields) {
        return new DesiredNode(
                NodeId.of(id),
                new YamlNodeSpec(MONITORING_TYPE, HumanGating.NONE, specFields),
                HumanGating.NONE);
    }
}
