package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.ActualStateAdapter;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.NodeType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MockActualStateAdapter implements ActualStateAdapter {

    private final ConcurrentHashMap<NodeId, NodeStatus> statuses = new ConcurrentHashMap<>();
    private volatile Set<NodeType> handledTypes = Set.of();

    @Override
    public Set<NodeType> handledTypes() {
        return handledTypes;
    }

    public void setHandledTypes(Set<NodeType> types) {
        this.handledTypes = Set.copyOf(types);
    }

    @Override
    public ActualState readActual(DesiredStateGraph desired, String tenancyId) {
        return new ActualState(new HashMap<>(statuses));
    }

    public void setStatus(NodeId nodeId, NodeStatus status) {
        statuses.put(nodeId, status);
    }

    public void setStatuses(Map<NodeId, NodeStatus> newStatuses) {
        statuses.clear();
        statuses.putAll(newStatuses);
    }

    public void makePresent(NodeId id) {
        setStatus(id, NodeStatus.PRESENT);
    }

    public void makeAbsent(NodeId id) {
        setStatus(id, NodeStatus.ABSENT);
    }

    public void setAllPresent(DesiredStateGraph desired) {
        desired.nodes().keySet().forEach(nodeId -> statuses.put(nodeId, NodeStatus.PRESENT));
    }

    public void clear() {
        statuses.clear();
        handledTypes = Set.of();
    }

    public Map<NodeId, NodeStatus> statuses() {
        return Map.copyOf(statuses);
    }
}
