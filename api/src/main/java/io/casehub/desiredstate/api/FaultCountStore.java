package io.casehub.desiredstate.api;

import java.util.Set;

public interface FaultCountStore {
    int incrementAndGet(String namespace, String tenancyId, NodeId nodeId);
    int getCount(String namespace, String tenancyId, NodeId nodeId);
    void reset(String namespace, String tenancyId, NodeId nodeId);
    void remove(String namespace, String tenancyId, NodeId nodeId);
    void evict(String namespace, String tenancyId, Set<NodeId> retainedNodes);
}
