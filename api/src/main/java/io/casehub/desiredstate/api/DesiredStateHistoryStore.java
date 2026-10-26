package io.casehub.desiredstate.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DesiredStateHistoryStore {
    long record(String tenancyId, DesiredStateGraph graph, String source, String initiatedBy);
    Optional<DesiredStateGraph> load(String tenancyId, long version);
    List<HistoryEntry> listVersions(String tenancyId);
    void prune(String tenancyId, int keepCount);
    void remove(String tenancyId);
    default Set<String> allTenancyIds() {
        throw new UnsupportedOperationException();
    }
}
