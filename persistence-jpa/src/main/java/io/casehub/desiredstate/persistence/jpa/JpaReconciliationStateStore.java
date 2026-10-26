package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.ReconciliationStateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class JpaReconciliationStateStore implements ReconciliationStateStore {

    @Inject
    EntityManager em;

    @Inject
    DesiredStateGraphFactory graphFactory;

    private final GraphSerializer serializer = new GraphSerializer();

    @Override
    @Transactional
    public void store(String tenancyId, DesiredStateGraph lastReconciledDesired) {
        String json = serializer.serialize(lastReconciledDesired);
        ReconciliationStateEntity entity = em.find(ReconciliationStateEntity.class, tenancyId);
        if (entity == null) {
            entity = new ReconciliationStateEntity();
            entity.tenancyId = tenancyId;
            entity.graphJson = json;
            entity.updatedAt = Instant.now();
            em.persist(entity);
        } else {
            entity.graphJson = json;
            entity.updatedAt = Instant.now();
        }
        em.flush();
    }

    @Override
    @Transactional
    public Optional<DesiredStateGraph> load(String tenancyId) {
        ReconciliationStateEntity entity = em.find(ReconciliationStateEntity.class, tenancyId);
        if (entity == null) {
            return Optional.empty();
        }
        DesiredStateGraph graph = serializer.deserialize(entity.graphJson, graphFactory);
        return Optional.ofNullable(graph);
    }

    @Override
    @Transactional
    public void remove(String tenancyId) {
        ReconciliationStateEntity entity = em.find(ReconciliationStateEntity.class, tenancyId);
        if (entity != null) {
            em.remove(entity);
            em.flush();
        }
    }

    @Override
    @Transactional
    public java.util.Set<String> allTenancyIds() {
        var ids = em.createQuery("SELECT DISTINCT e.tenancyId FROM ReconciliationStateEntity e", String.class)
                    .getResultList();
        return java.util.Set.copyOf(ids);
    }
}
