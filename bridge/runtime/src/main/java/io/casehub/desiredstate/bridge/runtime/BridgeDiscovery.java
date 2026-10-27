package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class BridgeDiscovery {

    private BridgeDiscovery() {}

    public static List<BeanRegistration> discoverAll(
            List<BridgeDescriptor> descriptors,
            List<BridgeProvider> providers) {

        Map<String, BridgeProvider> providersByProtocol = providers.stream()
            .collect(Collectors.toMap(BridgeProvider::protocolId, Function.identity()));

        List<BeanRegistration> beans = new ArrayList<>();

        for (var descriptor : descriptors) {
            var provider = providersByProtocol.get(descriptor.protocolId());
            if (provider == null) continue;

            var provisioner = new BridgeProvisioner(descriptor, provider);
            var adapter = new BridgeActualStateAdapter(descriptor, provider);

            beans.add(new BeanRegistration(
                "bridge-provisioner-" + descriptor.protocolId(),
                NodeProvisioner.class, provisioner));
            beans.add(new BeanRegistration(
                "bridge-adapter-" + descriptor.protocolId(),
                ActualStateAdapter.class, adapter));
        }

        return beans;
    }
}
