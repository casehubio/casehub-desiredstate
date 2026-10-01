package io.casehub.desiredstate.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryExemptionStore implements ExemptionStore {

    private record Key(String tenancyId, NodeId nodeId) {}

    private final ConcurrentHashMap<Key, Exemption> exemptions = new ConcurrentHashMap<>();

    @Override
    public void grant(String tenancyId, NodeId nodeId, Exemption exemption) {
        exemptions.put(new Key(tenancyId, nodeId), exemption);
    }

    @Override
    public void revoke(String tenancyId, NodeId nodeId) {
        exemptions.remove(new Key(tenancyId, nodeId));
    }

    @Override
    public Optional<Exemption> get(String tenancyId, NodeId nodeId) {
        return Optional.ofNullable(exemptions.get(new Key(tenancyId, nodeId)));
    }

    @Override
    public Map<NodeId, Exemption> getActive(String tenancyId) {
        return exemptions.entrySet().stream()
            .filter(e -> e.getKey().tenancyId().equals(tenancyId))
            .collect(Collectors.toMap(e -> e.getKey().nodeId(), Map.Entry::getValue));
    }

    @Override
    public void evict(String tenancyId, Set<NodeId> retainedNodes) {
        exemptions.keySet().removeIf(key ->
            key.tenancyId().equals(tenancyId) && !retainedNodes.contains(key.nodeId()));
    }
}
