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
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;
import io.casehub.platform.api.credentials.CredentialResolver;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class YamlPluginActualStateAdapter implements ActualStateAdapter {

    private final Map<NodeType, PluginDescriptor> plugins;
    private final ActualStateStepExecutor         actualStateExecutor;
    private final CredentialResolver              credentialResolver;

    public YamlPluginActualStateAdapter(Map<NodeType, PluginDescriptor> plugins,
                                        StructuralStepEvaluator evaluator,
                                        StepRunner runner,
                                        CredentialResolver credentialResolver) {
        this.plugins             = Map.copyOf(plugins);
        this.actualStateExecutor = new ActualStateStepExecutor(evaluator, runner);
        this.credentialResolver  = credentialResolver;
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
                    PluginDescriptor plugin   = plugins.get(node.type());
                    VariableResolver resolver = buildResolver(node, plugin);
                    NodeStatus status = actualStateExecutor.execute(
                            plugin.actualStateSteps(), resolver);
                    states.put(node.id(), status);
                } catch (RuntimeException e) {
                    states.put(node.id(), NodeStatus.UNKNOWN);
                }
            }
        }
        return new ActualState(states);
    }

    private VariableResolver buildResolver(DesiredNode node, PluginDescriptor plugin) {
        Map<String, Object>              specFields = extractSpecFields(node);
        Map<String, Map<String, String>> authMap    = new HashMap<>();
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

    private Map<String, Object> extractSpecFields(DesiredNode node) {
        if (node.spec() instanceof YamlNodeSpec yamlSpec) {
            return yamlSpec.fields();
        }
        return Map.of();
    }
}
