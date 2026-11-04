package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DiscoveredNode;
import io.casehub.desiredstate.api.DiscoveryAdapter;
import io.casehub.desiredstate.api.NodeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultDiscoveryAdapterRouter {

    private final Map<NodeType, DiscoveryAdapter> routing;

    protected DefaultDiscoveryAdapterRouter() {
        this.routing = Map.of();
    }

    public DefaultDiscoveryAdapterRouter(Collection<DiscoveryAdapter> adapters) {
        Map<NodeType, DiscoveryAdapter> table = new LinkedHashMap<>();
        for (DiscoveryAdapter adapter : adapters) {
            for (NodeType type : adapter.handledTypes()) {
                DiscoveryAdapter existing = table.put(type, adapter);
                if (existing != null) {
                    throw new IllegalArgumentException(
                        "NodeType " + type.value() + " claimed by both "
                        + existing.getClass().getName() + " and "
                        + adapter.getClass().getName());
                }
            }
        }
        this.routing = Map.copyOf(table);
    }

    public List<DiscoveredNode> discover(Set<NodeType> types, String tenancyId) {
        List<DiscoveredNode> results = new ArrayList<>();
        for (NodeType type : types) {
            DiscoveryAdapter adapter = routing.get(type);
            if (adapter != null) {
                results.addAll(adapter.discover(Set.of(type), tenancyId));
            }
        }
        return results;
    }

    public Set<NodeType> allHandledTypes() {
        return routing.keySet();
    }
}
