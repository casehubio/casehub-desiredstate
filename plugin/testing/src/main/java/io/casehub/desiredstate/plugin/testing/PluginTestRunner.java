package io.casehub.desiredstate.plugin.testing;

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
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
import io.casehub.desiredstate.plugin.runtime.YamlPluginActualStateAdapter;
import io.casehub.yaml.step.testing.FaultInjectingStepRunner;
import io.casehub.yaml.step.testing.TestCredentialResolver;
import io.casehub.desiredstate.plugin.runtime.YamlPluginProvisioner;
import io.casehub.desiredstate.plugin.runtime.primitives.CompareStatePrimitive;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.yaml.core.condition.ConditionEvaluator;
import io.casehub.yaml.plugin.api.MapServiceRegistry;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PluginTestRunner {

    private static final Pattern INFRA_VAR = Pattern.compile("\\$\\{([^}]+)}");

    private final PluginDescriptor descriptor;
    private final CredentialResolver credentialResolver;
    private final ConditionEvaluator conditionEvaluator;
    private final StructuralStepEvaluator evaluator;

    public PluginTestRunner(PluginDescriptor descriptor, Map<String, Object> authVariables) {
        this.descriptor = descriptor;
        this.credentialResolver = new TestCredentialResolver(
            descriptor.authCredentialRefs(), authVariables);
        this.conditionEvaluator = new ConditionEvaluator(
            PluginTestRunner::evaluateExpression);
        this.evaluator = new StructuralStepEvaluator(conditionEvaluator);
    }

    public ProvisionResult runProvision(Map<String, Object> specFields) {
        return runProvision(specFields, createRunner());
    }

    public ProvisionResult runProvisionWithFaultInjection(Map<String, Object> specFields,
                                                           int failCount, String error) {
        return runProvision(specFields,
            new FaultInjectingStepRunner(createRunner(), failCount, error));
    }

    public DeprovisionResult runDeprovision(Map<String, Object> specFields) {
        var node = buildNode(specFields);
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var provisioner = new YamlPluginProvisioner(
            Map.of(nodeType(), descriptor), evaluator, createRunner(), credentialResolver);
        return provisioner.deprovision(node, new DeprovisionContext("test-tenant", graph));
    }

    public NodeStatus runActualState(Map<String, Object> specFields) {
        var node = buildNode(specFields);
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var adapter = new YamlPluginActualStateAdapter(
            Map.of(nodeType(), descriptor), evaluator, createRunner(), credentialResolver);
        var actual = adapter.readActual(graph, "test-tenant");
        return actual.statusOf(node.id()).orElse(NodeStatus.UNKNOWN);
    }

    public Map<String, Object> resolveInfraBindings(Map<String, Object> specFields,
                                                      Map<String, Object> infraBindings) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : specFields.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                resolved.put(entry.getKey(), resolveInfraVarsInString(s, infraBindings));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private ProvisionResult runProvision(Map<String, Object> specFields, StepRunner runner) {
        var node = buildNode(specFields);
        var graph = new DefaultDesiredStateGraphFactory().empty().withNode(node);
        var provisioner = new YamlPluginProvisioner(
            Map.of(nodeType(), descriptor), evaluator, runner, credentialResolver);
        return provisioner.provision(node, new ProvisionContext("test-tenant", graph));
    }

    private DesiredNode buildNode(Map<String, Object> specFields) {
        NodeType type = nodeType();
        var spec = new YamlNodeSpec(type, HumanGating.NONE, specFields);
        NodeId id = NodeId.of("test-node");
        return new DesiredNode(id, spec, HumanGating.NONE);
    }

    private NodeType nodeType() {
        return NodeType.of(descriptor.type());
    }

    private StepRunner createRunner() {
        var comparePrimitive = new CompareStatePrimitive(conditionEvaluator);
        return (step, resolver) -> {
            if (step instanceof ResolvedStep.PluginStep ps) {
                Map<String, Object> resolved = resolver.resolveMap(ps.params(), "step");
                return switch (ps.name()) {
                    case "assert" -> {
                        String condition = (String) resolved.get("condition");
                        if (conditionEvaluator.evaluate(condition)) {
                            yield Result.of(Map.of());
                        }
                        throw new RuntimeException("Assertion failed: " + condition);
                    }
                    case "compare-state" -> comparePrimitive.execute(resolved,
                        new MapServiceRegistry());
                    default -> Result.failed("unknown step: " + ps.name());
                };
            }
            return Result.failed("unsupported step type");
        };
    }

    private static String resolveInfraVarsInString(String template,
                                                     Map<String, Object> infraBindings) {
        Matcher m = INFRA_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            Object replacement = infraBindings.get(ref);
            if (replacement != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean evaluateExpression(String expr) {
        String trimmed = expr.trim();
        if (trimmed.contains("!=")) {
            String[] parts = trimmed.split("!=", 2);
            return !parts[0].trim().equals(parts[1].trim());
        }
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
}
