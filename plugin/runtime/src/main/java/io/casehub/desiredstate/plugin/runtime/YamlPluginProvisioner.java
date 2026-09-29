package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.step.catalog.ResolvedStep;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
import io.casehub.platform.api.credentials.CredentialResolver;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

public class YamlPluginProvisioner implements NodeProvisioner {

    private final Map<NodeType, PluginDescriptor> plugins;
    private final StructuralStepEvaluator         evaluator;
    private final StepRunner                      runner;
    private final CredentialResolver              credentialResolver;

    public YamlPluginProvisioner(Map<NodeType, PluginDescriptor> plugins,
                                 StructuralStepEvaluator evaluator,
                                 StepRunner runner,
                                 CredentialResolver credentialResolver) {
        this.plugins            = Map.copyOf(plugins);
        this.evaluator          = evaluator;
        this.runner             = runner;
        this.credentialResolver = credentialResolver;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return plugins.keySet();
    }

    @Override
    public Duration resyncInterval() {
        return plugins.values().stream()
                      .map(PluginDescriptor::resyncInterval)
                      .min(Duration::compareTo)
                      .orElse(Duration.ofMinutes(5));
    }

    @Override
    public ProvisionResult provision(DesiredNode node, ProvisionContext context) {
        PluginDescriptor plugin = plugins.get(node.type());
        if (plugin == null) {
            return new ProvisionResult.Failed(
                    "No plugin registered for type: " + node.type());
        }
        try {
            VariableResolver resolver = buildResolver(node, plugin);
            ResolvedStep     block    = new ResolvedStep.BlockStep(null, plugin.provisionSteps(), Map.of());
            evaluator.evaluate(block, resolver, runner);
            return new ProvisionResult.Success();
        } catch (RuntimeException e) {
            return new ProvisionResult.Failed(e.getMessage());
        }
    }

    @Override
    public DeprovisionResult deprovision(DesiredNode node, DeprovisionContext context) {
        PluginDescriptor plugin = plugins.get(node.type());
        if (plugin == null) {
            return new DeprovisionResult.Failed(
                    "No plugin registered for type: " + node.type());
        }
        try {
            VariableResolver resolver = buildResolver(node, plugin);
            ResolvedStep     block    = new ResolvedStep.BlockStep(null, plugin.deprovisionSteps(), Map.of());
            evaluator.evaluate(block, resolver, runner);
            return new DeprovisionResult.Success();
        } catch (RuntimeException e) {
            return new DeprovisionResult.Failed(e.getMessage());
        }
    }

    private VariableResolver buildResolver(DesiredNode node, PluginDescriptor plugin) {
        Map<String, Object>              specFields = extractSpecFields(node);
        Map<String, Map<String, String>> authMap    = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : plugin.authCredentialRefs().entrySet()) {
            authMap.put(entry.getKey(), credentialResolver.resolve(entry.getValue()));
        }

        VariableResolver resolver = new VariableResolver(Map.of(), Set.of());
        resolver = resolver.withObjectScope("spec", specFields::get);
        if (!authMap.isEmpty()) {
            resolver = resolver.withObjectScope("auth", name -> authMap.get(name));
        }
        return resolver;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSpecFields(DesiredNode node) {
        if (node.spec() instanceof YamlNodeSpec yamlSpec) {
            return yamlSpec.fields();
        }
        return Map.of();
    }
}
