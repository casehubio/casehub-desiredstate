package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DeprovisionContext;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ProvisionContext;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.plugin.api.StepContext;
import io.casehub.desiredstate.plugin.api.StepResult;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.platform.api.credentials.CredentialResolver;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

public class YamlPluginProvisioner implements NodeProvisioner {

    private final Map<NodeType, PluginDescriptor> plugins;
    private final StepPipelineExecutor executor;
    private final CredentialResolver credentialResolver;

    public YamlPluginProvisioner(Map<NodeType, PluginDescriptor> plugins,
                                 StepPipelineExecutor executor,
                                 CredentialResolver credentialResolver) {
        this.plugins = Map.copyOf(plugins);
        this.executor = executor;
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
            StepContext stepContext = buildContext(node, plugin);
            executor.execute(plugin.provisionSteps(), stepContext);
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
            StepContext stepContext = buildContext(node, plugin);
            executor.execute(plugin.deprovisionSteps(), stepContext);
            return new DeprovisionResult.Success();
        } catch (RuntimeException e) {
            return new DeprovisionResult.Failed(e.getMessage());
        }
    }

    private StepContext buildContext(DesiredNode node, PluginDescriptor plugin) {
        StepContext.Builder builder = StepContext.builder()
            .spec(extractSpecFields(node));

        for (Map.Entry<String, String> entry : plugin.authCredentialRefs().entrySet()) {
            Map<String, String> credentials =
                credentialResolver.resolve(entry.getValue());
            builder.addAuth(entry.getKey(), credentials);
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSpecFields(DesiredNode node) {
        if (node.spec() instanceof YamlNodeSpec yamlSpec) {
            return yamlSpec.fields();
        }
        return Map.of();
    }
}
