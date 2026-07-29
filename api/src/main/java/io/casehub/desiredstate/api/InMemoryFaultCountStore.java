package io.casehub.desiredstate.api;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFaultCountStore implements FaultCountStore {

    private record Key(String namespace, String tenancyId, NodeId nodeId) {}

    private final ConcurrentHashMap<Key, Integer> counts = new ConcurrentHashMap<>();

    @Override
    public int incrementAndGet(String namespace, String tenancyId, NodeId nodeId) {
        return counts.merge(new Key(namespace, tenancyId, nodeId), 1, Integer::sum);
    }

    @Override
    public int getCount(String namespace, String tenancyId, NodeId nodeId) {
        return counts.getOrDefault(new Key(namespace, tenancyId, nodeId), 0);
    }

    @Override
    public void reset(String namespace, String tenancyId, NodeId nodeId) {
        counts.put(new Key(namespace, tenancyId, nodeId), 0);
    }

    @Override
    public void remove(String namespace, String tenancyId, NodeId nodeId) {
        counts.remove(new Key(namespace, tenancyId, nodeId));
    }

    @Override
    public void evict(String namespace, String tenancyId, Set<NodeId> retainedNodes) {
        counts.keySet().removeIf(key ->
                key.namespace().equals(namespace)
                        && key.tenancyId().equals(tenancyId)
                        && !retainedNodes.contains(key.nodeId()));
    }
}
