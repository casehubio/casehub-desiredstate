package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.ActualStateAdapterRouter;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicActualStateAdapterRouter implements ActualStateAdapterRouter {

    private final ConcurrentHashMap<NodeType, ActualStateAdapter> routing = new ConcurrentHashMap<>();

    public void register(ActualStateAdapter adapter) {
        Set<NodeType> types = adapter.handledTypes();
        for (NodeType type : types) {
            if (routing.containsKey(type)) {
                throw new IllegalStateException(
                        "Type collision: " + type.value() + " already handled by " + routing.get(type).getClass().getName());
            }
        }
        for (NodeType type : types) {
            routing.put(type, adapter);
        }
    }

    public void unregister(NodeType type) {
        routing.remove(type);
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
        return Set.copyOf(routing.keySet());
    }
}
