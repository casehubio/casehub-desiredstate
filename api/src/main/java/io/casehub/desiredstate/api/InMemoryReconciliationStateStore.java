package io.casehub.desiredstate.api;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryReconciliationStateStore implements ReconciliationStateStore {

    private final ConcurrentHashMap<String, DesiredStateGraph> snapshots = new ConcurrentHashMap<>();

    @Override
    public void store(String tenancyId, DesiredStateGraph lastReconciledDesired) {
        snapshots.put(tenancyId, lastReconciledDesired);
    }

    @Override
    public Optional<DesiredStateGraph> load(String tenancyId) {
        return Optional.ofNullable(snapshots.get(tenancyId));
    }

    @Override
    public void remove(String tenancyId) {
        snapshots.remove(tenancyId);
    }

    @Override
    public java.util.Set<String> allTenancyIds() {
        return java.util.Set.copyOf(snapshots.keySet());
    }
}
