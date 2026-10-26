package io.casehub.desiredstate.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryDesiredStateHistoryStore implements DesiredStateHistoryStore {

    private record VersionedGraph(long version, DesiredStateGraph graph, Instant recordedAt,
                                   String source, String initiatedBy) {}

    private final ConcurrentHashMap<String, List<VersionedGraph>> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    @Override
    public long record(String tenancyId, DesiredStateGraph graph, String source, String initiatedBy) {
        long version = sequences
            .computeIfAbsent(tenancyId, k -> new AtomicLong(0))
            .incrementAndGet();
        history
            .computeIfAbsent(tenancyId, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(new VersionedGraph(version, graph, Instant.now(), source, initiatedBy));
        return version;
    }

    @Override
    public Optional<DesiredStateGraph> load(String tenancyId, long version) {
        List<VersionedGraph> entries = history.get(tenancyId);
        if (entries == null) return Optional.empty();
        synchronized (entries) {
            return entries.stream()
                .filter(v -> v.version() == version)
                .map(VersionedGraph::graph)
                .findFirst();
        }
    }

    @Override
    public List<HistoryEntry> listVersions(String tenancyId) {
        List<VersionedGraph> entries = history.get(tenancyId);
        if (entries == null) return List.of();
        synchronized (entries) {
            return entries.stream()
                .sorted(Comparator.comparingLong(VersionedGraph::version).reversed())
                .map(v -> new HistoryEntry(v.version(), v.recordedAt(), v.source(), v.initiatedBy()))
                .toList();
        }
    }

    @Override
    public void prune(String tenancyId, int keepCount) {
        List<VersionedGraph> entries = history.get(tenancyId);
        if (entries == null) return;
        synchronized (entries) {
            if (entries.size() <= keepCount) return;
            entries.sort(Comparator.comparingLong(VersionedGraph::version));
            int removeCount = entries.size() - keepCount;
            entries.subList(0, removeCount).clear();
        }
    }

    @Override
    public void remove(String tenancyId) {
        history.remove(tenancyId);
        sequences.remove(tenancyId);
    }

    @Override
    public Set<String> allTenancyIds() {
        return Set.copyOf(history.keySet());
    }
}
