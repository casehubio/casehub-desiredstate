package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateHistoryStore;
import io.casehub.desiredstate.api.HistoryEntry;
import io.casehub.desiredstate.api.InMemoryDesiredStateHistoryStore;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DefaultDesiredStateHistoryStore implements DesiredStateHistoryStore {

    private final InMemoryDesiredStateHistoryStore delegate = new InMemoryDesiredStateHistoryStore();

    @Override
    public long record(String tenancyId, DesiredStateGraph graph, String source, String initiatedBy) {
        return delegate.record(tenancyId, graph, source, initiatedBy);
    }

    @Override
    public Optional<DesiredStateGraph> load(String tenancyId, long version) {
        return delegate.load(tenancyId, version);
    }

    @Override
    public List<HistoryEntry> listVersions(String tenancyId) {
        return delegate.listVersions(tenancyId);
    }

    @Override
    public void prune(String tenancyId, int keepCount) {
        delegate.prune(tenancyId, keepCount);
    }

    @Override
    public void remove(String tenancyId) {
        delegate.remove(tenancyId);
    }

    @Override
    public Set<String> allTenancyIds() {
        return delegate.allTenancyIds();
    }
}
