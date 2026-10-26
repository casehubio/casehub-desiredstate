package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.DesiredStateHistoryStore;
import io.casehub.desiredstate.api.HistoryEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class JpaDesiredStateHistoryStore implements DesiredStateHistoryStore {

    @Inject
    EntityManager em;

    @Inject
    DesiredStateGraphFactory graphFactory;

    private final GraphSerializer serializer = new GraphSerializer();

    @Override
    @Transactional
    public long record(String tenancyId, DesiredStateGraph graph, String source, String initiatedBy) {
        Long maxVersion = em.createQuery(
            "SELECT MAX(h.version) FROM DesiredStateHistoryEntity h WHERE h.tenancyId = :tid",
            Long.class)
            .setParameter("tid", tenancyId)
            .getSingleResult();
        long nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;

        var entity = new DesiredStateHistoryEntity();
        entity.tenancyId = tenancyId;
        entity.version = nextVersion;
        entity.graphJson = serializer.serialize(graph);
        entity.source = source;
        entity.initiatedBy = initiatedBy;
        entity.recordedAt = Instant.now();
        em.persist(entity);
        em.flush();
        return nextVersion;
    }

    @Override
    @Transactional
    public Optional<DesiredStateGraph> load(String tenancyId, long version) {
        var entity = em.find(DesiredStateHistoryEntity.class,
            new DesiredStateHistoryEntity.Key(tenancyId, version));
        if (entity == null) return Optional.empty();
        return Optional.ofNullable(serializer.deserialize(entity.graphJson, graphFactory));
    }

    @Override
    @Transactional
    public List<HistoryEntry> listVersions(String tenancyId) {
        return em.createQuery(
            "SELECT h FROM DesiredStateHistoryEntity h WHERE h.tenancyId = :tid ORDER BY h.version DESC",
            DesiredStateHistoryEntity.class)
            .setParameter("tid", tenancyId)
            .getResultList()
            .stream()
            .map(e -> new HistoryEntry(e.version, e.recordedAt, e.source, e.initiatedBy))
            .toList();
    }

    @Override
    @Transactional
    public void prune(String tenancyId, int keepCount) {
        List<Long> toKeep = em.createQuery(
            "SELECT h.version FROM DesiredStateHistoryEntity h WHERE h.tenancyId = :tid ORDER BY h.version DESC",
            Long.class)
            .setParameter("tid", tenancyId)
            .setMaxResults(keepCount)
            .getResultList();
        if (!toKeep.isEmpty()) {
            em.createQuery(
                "DELETE FROM DesiredStateHistoryEntity h WHERE h.tenancyId = :tid AND h.version NOT IN :keep")
                .setParameter("tid", tenancyId)
                .setParameter("keep", toKeep)
                .executeUpdate();
        }
    }

    @Override
    @Transactional
    public void remove(String tenancyId) {
        em.createQuery("DELETE FROM DesiredStateHistoryEntity h WHERE h.tenancyId = :tid")
            .setParameter("tid", tenancyId)
            .executeUpdate();
    }

    @Override
    @Transactional
    public Set<String> allTenancyIds() {
        var ids = em.createQuery(
            "SELECT DISTINCT h.tenancyId FROM DesiredStateHistoryEntity h", String.class)
            .getResultList();
        return Set.copyOf(ids);
    }
}
