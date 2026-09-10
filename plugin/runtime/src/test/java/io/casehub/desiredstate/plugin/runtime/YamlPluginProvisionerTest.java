package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.plugin.api.PluginInterpolator;
import io.casehub.desiredstate.plugin.api.StepParameters;
import io.casehub.desiredstate.plugin.api.StepPrimitive;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.plugin.model.PluginStepDef;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlPluginProvisionerTest {

    private static final NodeType TEST_TYPE = NodeType.of("test-resource");

    @Test
    void provisionDelegatesToStepPipeline() {
        var descriptor = createDescriptor(
            List.of(assertStep("1 == 1")),
            List.of(assertStep("1 == 1")));
        var provisioner = createProvisioner(descriptor);

        assertThat(provisioner.handledTypes()).containsExactly(TEST_TYPE);

        var node = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
            new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void deprovisionDelegatesToStepPipeline() {
        var descriptor = createDescriptor(
            List.of(assertStep("1 == 1")),
            List.of(assertStep("1 == 1")));
        var provisioner = createProvisioner(descriptor);

        var node = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.deprovision(node,
            new DeprovisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void failedStepReturnsFailedResult() {
        var descriptor = createDescriptor(
            List.of(assertStep("1 == 2")),
            List.of());
        var provisioner = createProvisioner(descriptor);

        var node = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
            new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void resyncIntervalFromDescriptor() {
        var descriptor = createDescriptorWithInterval(Duration.ofSeconds(30));
        var provisioner = createProvisioner(descriptor);

        assertThat(provisioner.resyncInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void specFieldsAvailableInSteps() {
        StepPrimitive specChecker = new StepPrimitive() {
            @Override
            public String name() { return "spec-check"; }

            @Override
            public StepResult execute(StepParameters params, io.casehub.desiredstate.plugin.api.StepContext context) {
                Object name = context.resolve("spec.name");
                if (!"my-resource".equals(name)) {
                    throw new StepExecutionException("Expected 'my-resource' but got: " + name);
                }
                return StepResult.of(Map.of("verified", true));
            }
        };

        var registry = PrimitiveRegistry.of(Map.of("spec-check", specChecker));
        var executor = new StepPipelineExecutor(registry, new PluginInterpolator());

        var descriptor = new PluginDescriptor(
            "test-resource", 1, Duration.ofMinutes(5), Map.of(),
            new PluginSpecSchema(Map.of()), List.of(),
            List.of(new PluginStepDef("spec-check", Map.of(), null, null, null, 3, null)),
            List.of(), List.of(), null, null);

        var provisioner = new YamlPluginProvisioner(
            Map.of(TEST_TYPE, descriptor), executor, ref -> Map.of());

        var node = createNode("n1", Map.of("name", "my-resource"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
            new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void authCredentialsResolvedAndAvailable() {
        StepPrimitive authChecker = new StepPrimitive() {
            @Override
            public String name() { return "auth-check"; }

            @Override
            public StepResult execute(StepParameters params, io.casehub.desiredstate.plugin.api.StepContext context) {
                Object token = context.resolve("auth.api.token");
                if (!"secret-token".equals(token)) {
                    throw new StepExecutionException("Expected 'secret-token' but got: " + token);
                }
                return StepResult.of(Map.of("verified", true));
            }
        };

        var registry = PrimitiveRegistry.of(Map.of("auth-check", authChecker));
        var executor = new StepPipelineExecutor(registry, new PluginInterpolator());

        var descriptor = new PluginDescriptor(
            "test-resource", 1, Duration.ofMinutes(5),
            Map.of("api", "api-credentials"),
            new PluginSpecSchema(Map.of()), List.of(),
            List.of(new PluginStepDef("auth-check", Map.of(), null, null, null, 3, null)),
            List.of(), List.of(), null, null);

        var provisioner = new YamlPluginProvisioner(
            Map.of(TEST_TYPE, descriptor), executor,
            ref -> Map.of("token", "secret-token", "endpoint", "api.example.com"));

        var node = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
            new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    private YamlPluginProvisioner createProvisioner(PluginDescriptor descriptor) {
        var registry = PrimitiveRegistry.of(Map.of(
            "assert", new io.casehub.desiredstate.plugin.runtime.primitives.AssertPrimitive()));
        var executor = new StepPipelineExecutor(registry, new PluginInterpolator());
        return new YamlPluginProvisioner(
            Map.of(TEST_TYPE, descriptor), executor, ref -> Map.of());
    }

    private static PluginDescriptor createDescriptor(List<PluginStepDef> provisionSteps,
                                                     List<PluginStepDef> deprovisionSteps) {
        return new PluginDescriptor(
            "test-resource", 1, Duration.ofMinutes(5), Map.of(),
            new PluginSpecSchema(Map.of()), List.of(),
            provisionSteps, deprovisionSteps, List.of(), null, null);
    }

    private static PluginDescriptor createDescriptorWithInterval(Duration interval) {
        return new PluginDescriptor(
            "test-resource", 1, interval, Map.of(),
            new PluginSpecSchema(Map.of()), List.of(),
            List.of(assertStep("1 == 1")), List.of(), List.of(), null, null);
    }

    private static PluginStepDef assertStep(String condition) {
        return new PluginStepDef("assert", Map.of("condition", condition),
            null, null, null, 3, null);
    }

    private static DesiredNode createNode(String id, Map<String, Object> specFields) {
        return new DesiredNode(
            NodeId.of(id),
            new YamlNodeSpec(TEST_TYPE, HumanGating.NONE, specFields),
            HumanGating.NONE);
    }
}
