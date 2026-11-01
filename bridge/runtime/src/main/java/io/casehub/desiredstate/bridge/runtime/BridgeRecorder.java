package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import org.jboss.logging.Logger;

@Recorder
public class BridgeRecorder {

    private static final Logger LOG = Logger.getLogger(BridgeRecorder.class);

    public RuntimeValue<NodeProvisioner> createProvisioner(BridgeDescriptor descriptor) {
        BridgeProvider provider = findProvider(descriptor.protocolId());
        if (provider == null) {
            LOG.warnf("No BridgeProvider bean for protocol '%s' — bridge provisioner not created",
                    descriptor.protocolId());
            return new RuntimeValue<>(null);
        }
        return new RuntimeValue<>(new BridgeProvisioner(descriptor, provider));
    }

    public RuntimeValue<ActualStateAdapter> createActualStateAdapter(BridgeDescriptor descriptor) {
        BridgeProvider provider = findProvider(descriptor.protocolId());
        if (provider == null) {
            return new RuntimeValue<>(null);
        }
        return new RuntimeValue<>(new BridgeActualStateAdapter(descriptor, provider));
    }

    private BridgeProvider findProvider(String protocolId) {
        return Arc.container().select(BridgeProvider.class).stream()
                .filter(p -> p.protocolId().equals(protocolId))
                .findFirst()
                .orElse(null);
    }
}
