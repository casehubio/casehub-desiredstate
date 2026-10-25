package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.yaml.step.eval.StepRunner;
import io.casehub.yaml.step.eval.StructuralStepEvaluator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PluginDiscovery {

    public List<BeanRegistration> discover(List<PluginModel> plugins) {
        List<BeanRegistration> beans = new ArrayList<>();
        for (PluginModel plugin : plugins) {
            PluginDescriptor descriptor = toDescriptor(plugin);
            beans.add(new BeanRegistration("pluginDescriptor_" + descriptor.type(),
                PluginDescriptor.class, descriptor));
        }
        return beans;
    }

    public List<BeanRegistration> discoverAll(List<PluginModel> plugins,
                                               StructuralStepEvaluator evaluator,
                                               StepRunner runner,
                                               CredentialResolver credentialResolver) {
        List<BeanRegistration> beans = new ArrayList<>();
        Map<NodeType, PluginDescriptor> descriptorMap = new LinkedHashMap<>();

        for (PluginModel plugin : plugins) {
            PluginDescriptor descriptor = toDescriptor(plugin);
            String type = descriptor.type();
            beans.add(new BeanRegistration("pluginDescriptor_" + type,
                PluginDescriptor.class, descriptor));
            descriptorMap.put(NodeType.of(type), descriptor);

            if (plugin.ras() != null) {
                beans.add(new BeanRegistration("pluginRas_" + type,
                    YamlPluginRasRegistrar.class,
                    new YamlPluginRasRegistrar(type, plugin.ras())));
            }

            if (plugin.cbr() != null) {
                beans.add(new BeanRegistration("pluginCbr_" + type,
                    CbrPluginMetadata.class,
                    CbrPluginMetadata.from(type, plugin.cbr())));
            }
        }

        if (!descriptorMap.isEmpty()) {
            beans.add(new BeanRegistration("yamlPluginProvisioner",
                NodeProvisioner.class,
                new YamlPluginProvisioner(descriptorMap, evaluator, runner,
                    credentialResolver)));

            beans.add(new BeanRegistration("yamlPluginActualStateAdapter",
                ActualStateAdapter.class,
                new YamlPluginActualStateAdapter(descriptorMap, evaluator, runner,
                    credentialResolver)));
        }

        return beans;
    }

    private static PluginDescriptor toDescriptor(PluginModel plugin) {
        String type = plugin.header().type();
        Map<String, String> authRefs = plugin.header().auth() != null
                                       ? plugin.header().auth().entrySet().stream()
                                               .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().credentialRef()))
                                       : Map.of();
        Duration resync = plugin.header().resyncInterval() != null
                          ? Duration.parse("PT" + plugin.header().resyncInterval())
                          : Duration.ofMinutes(5);
        return new PluginDescriptor(
                type, plugin.header().version(), resync, authRefs,
                plugin.spec(), plugin.actualStateSteps(),
                plugin.provisioner().provisionSteps(),
                plugin.provisioner().deprovisionSteps(),
                plugin.provisioner().suspendSteps(),
                plugin.provisioner().resumeSteps(),
                plugin.faultPolicies(), plugin.cbr(), plugin.ras());
    }
}
