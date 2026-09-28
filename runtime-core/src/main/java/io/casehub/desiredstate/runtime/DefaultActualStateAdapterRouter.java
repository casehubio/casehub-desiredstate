package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class DefaultActualStateAdapterRouter implements ActualStateAdapterRouter {

    private final Map<NodeType, ActualStateAdapter> routing;


    protected DefaultActualStateAdapterRouter() {
        this.routing = Map.of();
    }

    public DefaultActualStateAdapterRouter(Collection<ActualStateAdapter> adapters) {
        Map<NodeType, ActualStateAdapter> table = new LinkedHashMap<>();
        for (ActualStateAdapter adapter : adapters) {
            for (NodeType type : adapter.handledTypes()) {
                ActualStateAdapter existing = table.put(type, adapter);
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

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        Set<ActualStateAdapter> allAdapters = new LinkedHashSet<>(routing.values());

        Set<NodeId> uncoveredNodes = new LinkedHashSet<>();
        for (DesiredNode node : desired.nodes().values()) {
            if (!routing.containsKey(node.type())) {
                uncoveredNodes.add(node.id());
            }
        }

        Map<NodeId, NodeStatus> merged = new HashMap<>();
        for (ActualStateAdapter adapter : allAdapters) {
            DesiredStateGraph filtered = desired.filterByTypes(adapter.handledTypes());
            ActualState partial = adapter.readActual(filtered, tenancyId);
            merged.putAll(partial.statuses());
        }

        for (NodeId uncovered : uncoveredNodes) {
            merged.put(uncovered, NodeStatus.UNKNOWN);
        }

        return new ActualState(merged);
    }

    @Override
    public Set<NodeType> allHandledTypes() {
        return routing.keySet();
    }
}
