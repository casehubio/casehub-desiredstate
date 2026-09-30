package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.plugin.model.PluginModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PluginDiscovery {

    public List<BeanRegistration> discover(List<PluginModel> plugins) {
        List<BeanRegistration> beans = new ArrayList<>();
        for (PluginModel plugin : plugins) {
            String type = plugin.header().type();
            Map<String, String> authRefs = plugin.header().auth() != null
                ? plugin.header().auth().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().credentialRef()))
                : Map.of();
            Duration resync = plugin.header().resyncInterval() != null
                ? Duration.parse("PT" + plugin.header().resyncInterval())
                : Duration.ofMinutes(5);
            PluginDescriptor descriptor = new PluginDescriptor(
                type, plugin.header().version(), resync, authRefs,
                plugin.spec(), plugin.actualStateSteps(),
                plugin.provisioner().provisionSteps(),
                plugin.provisioner().deprovisionSteps(),
                plugin.faultPolicies(), plugin.cbr(), plugin.ras());
            beans.add(new BeanRegistration("pluginDescriptor_" + type,
                PluginDescriptor.class, descriptor));
        }
        return beans;
    }
}
