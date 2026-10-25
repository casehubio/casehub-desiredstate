package io.casehub.desiredstate.plugin.runtime;

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
import io.casehub.desiredstate.plugin.model.PluginSpecSchema;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
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
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(pluginStep("assert", Map.of("condition", "true"))));
        var provisioner = createProvisioner(descriptor);

        assertThat(provisioner.handledTypes()).containsExactly(TEST_TYPE);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
                                           new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void deprovisionDelegatesToStepPipeline() {
        var descriptor = createDescriptor(
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(pluginStep("assert", Map.of("condition", "true"))));
        var provisioner = createProvisioner(descriptor);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.deprovision(node,
                                             new DeprovisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Success.class);
    }

    @Test
    void suspendDelegatesToStepPipeline() {
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(), List.of(),
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(), List.of(), null, null);
        var provisioner = createProvisioner(descriptor);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.suspend(node,
                                         new SuspendContext("tenant1", graph));

        assertThat(result).isInstanceOf(SuspendResult.Success.class);
    }

    @Test
    void resumeDelegatesToStepPipeline() {
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(), List.of(), List.of(),
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(), null, null);
        var provisioner = createProvisioner(descriptor);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.resume(node,
                                        new ResumeContext("tenant1", graph));

        assertThat(result).isInstanceOf(ResumeResult.Success.class);
    }

    @Test
    void suspendFailsWhenNoStepsDefined() {
        var descriptor = createDescriptor(
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of());
        var provisioner = createProvisioner(descriptor);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.suspend(node,
                                         new SuspendContext("tenant1", graph));

        assertThat(result).isInstanceOf(SuspendResult.Failed.class);
        assertThat(((SuspendResult.Failed) result).reason()).contains("suspend steps");
    }

    @Test
    void resumeFailsWhenNoStepsDefined() {
        var descriptor = createDescriptor(
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of());
        var provisioner = createProvisioner(descriptor);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.resume(node,
                                        new ResumeContext("tenant1", graph));

        assertThat(result).isInstanceOf(ResumeResult.Failed.class);
        assertThat(((ResumeResult.Failed) result).reason()).contains("resume steps");
    }

    @Test
    void supportsStatefulLifecycleWhenSuspendStepsDefined() {
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(), List.of(),
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(), List.of(), null, null);
        var provisioner = createProvisioner(descriptor);

        assertThat(provisioner.supportsStatefulLifecycle()).isTrue();
    }

    @Test
    void doesNotSupportStatefulLifecycleWhenNoSuspendResumeSteps() {
        var descriptor = createDescriptor(
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of());
        var provisioner = createProvisioner(descriptor);

        assertThat(provisioner.supportsStatefulLifecycle()).isFalse();
    }


    @Test
    void failedStepReturnsFailedResult() {
        var descriptor = createDescriptor(
                List.of(pluginStep("assert", Map.of("condition", "false"))),
                List.of());
        var provisioner = createProvisioner(descriptor);

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
                                           new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
    }

    @Test
    void resyncIntervalFromDescriptor() {
        var descriptor  = createDescriptorWithInterval(Duration.ofSeconds(30));
        var provisioner = createProvisioner(descriptor);

        assertThat(provisioner.resyncInterval()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void specFieldsAvailableInSteps() {
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(pluginStep("spec-check", Map.of("expected", "${spec.name}"))),
                List.of(), List.of(), List.of(), List.of(), null, null);

        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                Map<String, Object> resolved = resolver.resolveMap(ps.params(), "step");
                if ("my-resource".equals(resolved.get("expected"))) {
                    return Result.of(Map.of("verified", true));
                }
                throw new RuntimeException("Expected 'my-resource' but got: " + resolved.get("expected"));
            }
            return Result.failed("unsupported");
        };

        var evaluator = new StructuralStepEvaluator(new ConditionEvaluator(null));
        var provisioner = new YamlPluginProvisioner(
                Map.of(TEST_TYPE, descriptor), evaluator, runner, ref -> Map.of());

        var node  = createNode("n1", Map.of("name", "my-resource"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
                                           new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void authCredentialsResolvedAndAvailable() {
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5),
                Map.of("api", "api-credentials"),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(pluginStep("auth-check", Map.of("token", "${auth.api.token}"))),
                List.of(), List.of(), List.of(), List.of(), null, null);

        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                Map<String, Object> resolved = resolver.resolveMap(ps.params(), "step");
                if ("secret-token".equals(resolved.get("token"))) {
                    return Result.of(Map.of("verified", true));
                }
                throw new RuntimeException("Expected 'secret-token' but got: " + resolved.get("token"));
            }
            return Result.failed("unsupported");
        };

        var evaluator = new StructuralStepEvaluator(new ConditionEvaluator(null));
        var provisioner = new YamlPluginProvisioner(
                Map.of(TEST_TYPE, descriptor), evaluator, runner,
                ref -> Map.of("token", "secret-token", "endpoint", "api.example.com"));

        var node  = createNode("n1", Map.of("name", "x"));
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node,
                                           new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Success.class);
    }

    @Test
    void provisionRejectsInvalidSpec() {
        var schema = new PluginSpecSchema(Map.of(
                "name", new io.casehub.desiredstate.plugin.model.PluginFieldDef(
                        "string", true, null, null, null, null,
                        null, null, null, null, null, null, null)));
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                schema, List.of(),
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(), List.of(), List.of(), List.of(), null, null);
        var provisioner = createProvisioner(descriptor);

        var node   = createNode("n1", Map.of());
        var graph  = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.provision(node, new ProvisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(ProvisionResult.Failed.class);
        assertThat(((ProvisionResult.Failed) result).reason()).contains("name");
    }

    @Test
    void deprovisionRejectsInvalidSpec() {
        var schema = new PluginSpecSchema(Map.of(
                "region", new io.casehub.desiredstate.plugin.model.PluginFieldDef(
                        "enum", true, null, null, null, null,
                        null, null, List.of("us-east", "eu-west"), null, null, null, null)));
        var descriptor = new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                schema, List.of(), List.of(),
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(), List.of(), List.of(), null, null);
        var provisioner = createProvisioner(descriptor);

        var node   = createNode("n1", Map.of("region", "mars"));
        var graph  = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var result = provisioner.deprovision(node, new DeprovisionContext("tenant1", graph));

        assertThat(result).isInstanceOf(DeprovisionResult.Failed.class);
        assertThat(((DeprovisionResult.Failed) result).reason()).contains("mars");
    }


    private YamlPluginProvisioner createProvisioner(PluginDescriptor descriptor) {
        StepRunner runner = (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps && "assert".equals(ps.name())) {
                String condition = (String) ps.params().get("condition");
                if ("true".equals(condition)) {
                    return Result.of(Map.of());
                }
                throw new RuntimeException("Assertion failed: " + condition);
            }
            return Result.failed("unknown step");
        };
        var evaluator = new StructuralStepEvaluator(new ConditionEvaluator(null));
        return new YamlPluginProvisioner(
                Map.of(TEST_TYPE, descriptor), evaluator, runner, ref -> Map.of());
    }

    private static PluginDescriptor createDescriptor(List<ResolvedStep> provisionSteps,
                                                     List<ResolvedStep> deprovisionSteps) {
        return new PluginDescriptor(
                "test-resource", 1, Duration.ofMinutes(5), Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                provisionSteps, deprovisionSteps, List.of(), List.of(), List.of(), null, null);
    }

    private static PluginDescriptor createDescriptorWithInterval(Duration interval) {
        return new PluginDescriptor(
                "test-resource", 1, interval, Map.of(),
                new PluginSpecSchema(Map.of()), List.of(),
                List.of(pluginStep("assert", Map.of("condition", "true"))),
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    private static ResolvedStep pluginStep(String name, Map<String, Object> params) {
        return new ResolvedStep.PluginStep(name,
                                           name, params, Map.of());
    }

    private static DesiredNode createNode(String id, Map<String, Object> specFields) {
        return new DesiredNode(
                NodeId.of(id),
                new YamlNodeSpec(TEST_TYPE, HumanGating.NONE, specFields),
                HumanGating.NONE);
    }
}
