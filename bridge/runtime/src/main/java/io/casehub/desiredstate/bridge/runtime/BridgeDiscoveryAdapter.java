package io.casehub.desiredstate.bridge.runtime;

import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.DiscoveryAdapter;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.bridge.BridgeDescriptor;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.DiscoveredResource;
import io.casehub.desiredstate.bridge.DiscoveryCapable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BridgeDiscoveryAdapter implements DiscoveryAdapter {

    private final BridgeDescriptor descriptor;
    private final DiscoveryCapable provider;

    public BridgeDiscoveryAdapter(BridgeDescriptor descriptor, BridgeProvider provider) {
        if (!(provider instanceof DiscoveryCapable dc)) {
            throw new IllegalArgumentException("BridgeProvider must implement DiscoveryCapable");
        }
        this.descriptor = descriptor;
        this.provider = dc;
    }

    @Override
    public Set<NodeType> handledTypes() {
        return descriptor.handledTypes();
    }

    @Override
    public List<DiscoveredNode> discover(Set<NodeType> types, String tenancyId) {
        List<DiscoveredNode> nodes = new ArrayList<>();
        for (NodeType type : types) {
            if (!descriptor.handledTypes().contains(type)) continue;
            var typeDesc = descriptor.typeDescriptors().get(type.value());
            if (typeDesc == null) continue;

            List<DiscoveredResource> resources = provider.discover(type.value(), tenancyId);
            for (DiscoveredResource r : resources) {
                Map<String, Object> spec = Map.copyOf(r.state());
                nodes.add(new DiscoveredNode(r.resourceId(), type, r.state(), spec));
            }
        }
        return nodes;
    }
}
