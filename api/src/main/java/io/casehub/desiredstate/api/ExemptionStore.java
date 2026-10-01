package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface ExemptionStore {
    void grant(String tenancyId, NodeId nodeId, Exemption exemption);
    void revoke(String tenancyId, NodeId nodeId);
    Optional<Exemption> get(String tenancyId, NodeId nodeId);
    Map<NodeId, Exemption> getActive(String tenancyId);
    void evict(String tenancyId, Set<NodeId> retainedNodes);
}
