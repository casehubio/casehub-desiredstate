package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.plugin.api.YamlNodeSpec;
import io.casehub.yaml.core.resolver.VariableResolver;
import io.casehub.yaml.step.StepContext;
import io.casehub.yaml.step.StepPipelineExecutor;
import io.casehub.yaml.step.PrimitiveRegistry;
import io.casehub.platform.api.credentials.CredentialResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class YamlPluginActualStateAdapter implements ActualStateAdapter {

    private final Map<NodeType, PluginDescriptor> plugins;
    private final ActualStateStepExecutor actualStateExecutor;
    private final CredentialResolver credentialResolver;

    public YamlPluginActualStateAdapter(Map<NodeType, PluginDescriptor> plugins,
                                        StepPipelineExecutor executor,
                                        CredentialResolver credentialResolver) {
        this.plugins = Map.copyOf(plugins);
        this.actualStateExecutor = new ActualStateStepExecutor(executor);
        this.credentialResolver = credentialResolver;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return plugins.keySet();
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Map<NodeId, NodeStatus> states = new HashMap<>();

        for (DesiredNode node : desired.nodes().values()) {
            if (plugins.containsKey(node.type())) {
                try {
                    PluginDescriptor plugin = plugins.get(node.type());
                    StepContext context = buildContext(node, plugin);
                    VariableResolver resolver = context.toResolver();
                    NodeStatus status = actualStateExecutor.execute(
                        plugin.actualStateSteps(), context, resolver);
                    states.put(node.id(), status);
                } catch (RuntimeException e) {
                    states.put(node.id(), NodeStatus.UNKNOWN);
                }
            }
        }
        return new ActualState(states);
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

    private Map<String, Object> extractSpecFields(DesiredNode node) {
        if (node.spec() instanceof YamlNodeSpec yamlSpec) {
            return yamlSpec.fields();
        }
        return Map.of();
    }
}
